package com.careslot.it;

import com.careslot.scheduling.application.AppointmentLifecycle;
import com.careslot.scheduling.application.WaitlistService;
import com.careslot.shared.events.AppointmentEvent;
import com.careslot.support.Api;
import com.careslot.support.ItBase;
import com.careslot.support.World;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static com.careslot.support.World.at;
import static com.careslot.support.World.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SchedulingEventsIT extends ItBase {

    private final LocalDate tomorrow = World.today().plusDays(1);

    @Autowired
    WaitlistService waitlist;

    @Autowired
    AppointmentLifecycle lifecycle;

    @Test
    void aCancellationGoesToTheHighestPriorityWaitingPatientExactlyOnce() {
        World w = world();
        World.Person dr = w.doctor("Dr Wait One");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "09:15", 15, 1);
        UUID holder = w.patient().id();
        UUID early = w.patient().id();
        UUID urgent = w.patient().id();
        Instant from = at(tomorrow, "09:00");
        Instant to = at(tomorrow, "09:15");
        String appt = desk.post("/v1/appointments", w.consult(holder, dr.id(), from, to), key("h")).expect(201).text("id");
        Map<String, Object> window = Map.of("from", from.minusSeconds(3600).toString(), "to", to.plusSeconds(3600).toString());
        desk.post("/v1/waitlist", merge(window, Map.of("patientId", early, "practitionerId", dr.id(), "priority", 0)))
                .expect(201);
        String urgentEntry = desk.post("/v1/waitlist", merge(window, Map.of("patientId", urgent,
                "practitionerId", dr.id(), "priority", 5))).expect(201).text("id");

        desk.post("/v1/appointments/" + appt + "/cancel", Map.of("reason", "synthetic test")).expect(200);

        await().atMost(Duration.ofSeconds(20)).until(() -> "BOOKED".equals(jdbc.queryForObject(
                "select status from waitlist_entry where id = ?::uuid", String.class, urgentEntry)));
        UUID filled = jdbc.queryForObject("select appointment_id from waitlist_entry where id = ?::uuid", UUID.class,
                urgentEntry);
        assertThat(desk.get("/v1/appointments/" + filled).expect(200).text("patientId")).isEqualTo(urgent.toString());
        assertThat(jdbc.queryForObject("select status from waitlist_entry where patient_id = ?", String.class, early))
                .isEqualTo("WAITING");

        // the same CANCELLED event delivered again books nobody else
        UUID eventId = jdbc.queryForObject("""
                select event_id from outbox where payload->>'appointmentId' = ? and payload->>'type' = 'CANCELLED'""",
                UUID.class, appt);
        var again = new AppointmentEvent(eventId, AppointmentEvent.Type.CANCELLED, UUID.fromString(appt), holder, dr.id(),
                w.clinic, from, to, Instant.now());
        assertThat(waitlist.onFreed(again)).isEmpty();
        assertThat(jdbc.queryForObject("select booked from slot where resource_id = ? and lower(period) = ?",
                Integer.class, dr.id(), java.sql.Timestamp.from(from))).isEqualTo(1);
    }

    @Test
    void patientsCannotGiveThemselvesWaitlistPriority() {
        World w = world();
        World.Person dr = w.doctor("Dr Wait Two");
        World.Person p = w.patient();
        String id = p.api().post("/v1/waitlist", Map.of("patientId", p.id(), "practitionerId", dr.id(), "priority", 9,
                "from", at(tomorrow, "09:00").toString(), "to", at(tomorrow, "12:00").toString())).expect(201).text("id");
        assertThat(jdbc.queryForObject("select priority from waitlist_entry where id = ?::uuid", Integer.class, id)).isZero();
        p.api().post("/v1/waitlist", Map.of("patientId", p.id(), "practitionerId", dr.id(),
                "from", at(tomorrow, "09:00").toString(), "to", at(tomorrow, "12:00").toString())).expect(409);
    }

    @Test
    void availabilityIsCachedAndDroppedWhenABookingChangesTheDay() {
        World w = world();
        World.Person dr = w.doctor("Dr Cache");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "10:00", 15, 1);
        String path = "/v1/practitioners/" + dr.id() + "/availability?date=" + tomorrow;
        Api.Response first = desk.get(path).expect(200);
        assertThat(first.body().path("cached").asBoolean()).isFalse();
        assertThat(first.body().path("starts")).hasSize(4);
        assertThat(desk.get(path).expect(200).body().path("cached").asBoolean()).isTrue();

        desk.post("/v1/appointments", w.consult(w.patient().id(), dr.id(), at(tomorrow, "09:00"), at(tomorrow, "09:15")),
                key("c")).expect(201);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Api.Response after = desk.get(path).expect(200);
            assertThat(after.body().path("starts")).hasSize(3);
        });
    }

    @Test
    void leaveIsRefusedOverBookedPatientsAndBlocksFreeSlots() {
        World w = world();
        World.Person dr = w.doctor("Dr Leave");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "10:00", 15, 1);
        desk.post("/v1/appointments", w.consult(w.patient().id(), dr.id(), at(tomorrow, "09:00"), at(tomorrow, "09:15")),
                key("l")).expect(201);
        Api.Response clash = dr.api().post("/v1/resources/" + dr.id() + "/leaves", Map.of("start",
                at(tomorrow, "09:00").toString(), "end", at(tomorrow, "10:00").toString(), "reason", "conference"))
                .expect(409);
        assertThat(clash.text("code")).isEqualTo("leave-conflicts-bookings");
        Api.Response ok = dr.api().post("/v1/resources/" + dr.id() + "/leaves", Map.of("start",
                at(tomorrow, "09:15").toString(), "end", at(tomorrow, "10:00").toString(), "reason", "conference"))
                .expect(201);
        assertThat(ok.body().path("slotsBlocked").asInt()).isEqualTo(3);
        desk.post("/v1/appointments", w.consult(w.patient().id(), dr.id(), at(tomorrow, "09:15"), at(tomorrow, "10:00")),
                key("l2")).expect(409);
        World.Person other = w.doctor("Dr Not Mine");
        other.api().post("/v1/resources/" + dr.id() + "/leaves", Map.of("start", at(tomorrow.plusDays(1), "09:00").toString(),
                "end", at(tomorrow.plusDays(1), "10:00").toString(), "reason", "x")).expect(403);
    }

    @Test
    void replacingATemplateKeepsBookedPatientsAndOnlyChangesTheFuture() {
        World w = world();
        World.Person dr = w.doctor("Dr Template");
        String tpl = w.template(dr.id(), "FREQ=DAILY", "09:00", "10:00", 15, 1);
        desk.post("/v1/appointments", w.consult(w.patient().id(), dr.id(), at(tomorrow, "09:15"), at(tomorrow, "09:30")),
                key("t")).expect(201);
        admin.post("/v1/templates/" + tpl + "/replace", Map.of("effectiveFrom", World.today().minusDays(1).toString(),
                "template", spec())).expect(400);
        Api.Response r = admin.post("/v1/templates/" + tpl + "/replace", Map.of("effectiveFrom", tomorrow.toString(),
                "template", spec())).expect(201);
        assertThat(r.body().path("bookedSlotsKept").asInt()).isEqualTo(1);
        // the kept 09:15-09:30 slot blocks the new 09:00-09:30 slot; 09:30-10:00 is new
        Integer booked = jdbc.queryForObject("""
                select booked from slot where resource_id = ? and lower(period) = ?""", Integer.class, dr.id(),
                java.sql.Timestamp.from(at(tomorrow, "09:15")));
        assertThat(booked).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from slot where resource_id = ? and lower(period) = ? and upper(period) = ?""",
                Integer.class, dr.id(), java.sql.Timestamp.from(at(tomorrow, "09:30")),
                java.sql.Timestamp.from(at(tomorrow, "10:00")))).isEqualTo(1);
    }

    private Map<String, Object> spec() {
        return Map.of("rrule", "FREQ=DAILY", "dayStart", "09:00", "dayEnd", "10:00", "slotMinutes", 30, "capacity", 1,
                "validFrom", tomorrow.toString());
    }

    @Test
    void appointmentsLeftBookedPastTheGracePeriodBecomeNoShows() {
        World w = world();
        World.Person dr = w.doctor("Dr Sweep");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "09:15", 15, 1);
        String appt = desk.post("/v1/appointments", w.consult(w.patient().id(), dr.id(), at(tomorrow, "09:00"),
                at(tomorrow, "09:15")), key("s")).expect(201).text("id");
        // pretend the visit was two days ago
        jdbc.update("update appointment set period = tstzrange(now() - interval '2 days', now() - interval '2 days' + interval '15 minutes', '[)') where id = ?::uuid", appt);
        lifecycle.sweepNoShows();
        assertThat(desk.get("/v1/appointments/" + appt).expect(200).text("status")).isEqualTo("NO_SHOW");
        assertThat(jdbc.queryForObject("select no_shows from clinic_attendance where clinic_id = ?", Long.class, w.clinic))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from reminder where appointment_id = ?::uuid and status = 'PENDING'",
                Integer.class, appt)).isZero();
    }

    private static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        var m = new java.util.HashMap<>(a);
        m.putAll(b);
        return m;
    }
}
