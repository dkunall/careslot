package com.careslot.it;

import com.careslot.support.Api;
import com.careslot.support.ItBase;
import com.careslot.support.Race;
import com.careslot.support.World;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.careslot.support.World.at;
import static com.careslot.support.World.key;
import static org.assertj.core.api.Assertions.assertThat;

class BookingIT extends ItBase {

    private final LocalDate tomorrow = World.today().plusDays(1);

    @Test
    void manyPatientsRacingForOneSlotYieldExactlyOneBooking() {
        World w = world();
        World.Person dr = w.doctor("Dr Test One");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "10:00", 15, 1);
        int n = 40;
        List<UUID> patients = IntStream.range(0, n).mapToObj(i -> w.patient().id()).toList();
        Instant from = at(tomorrow, "09:00");
        Instant to = at(tomorrow, "09:15");

        List<Api.Response> results = Race.run(n, i -> desk.post("/v1/appointments",
                w.consult(patients.get(i), dr.id(), from, to), key("race-" + i)));

        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 409)
                .allSatisfy(r -> assertThat(r.body().path("code").asText()).isEqualTo("no-capacity"))
                .hasSize(n - 1);
        assertThat(jdbc.queryForObject("""
                select booked from slot where resource_id = ? and lower(period) = ?""", Integer.class,
                dr.id(), java.sql.Timestamp.from(from))).isEqualTo(1);
    }

    @Test
    void sameKeyReplaysAndADifferentBodyWithTheSameKeyIsRejected() {
        World w = world();
        World.Person dr = w.doctor("Dr Test Two");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "10:00", 15, 1);
        UUID p = w.patient().id();
        var body = w.consult(p, dr.id(), at(tomorrow, "09:00"), at(tomorrow, "10:00"));

        List<Api.Response> both = Race.run(8, i -> desk.post("/v1/appointments", body, key("same-key")));
        assertThat(both.stream().map(r -> r.text("id")).distinct()).hasSize(1);
        assertThat(both).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(both).filteredOn(r -> r.status() == 200)
                .allSatisfy(r -> assertThat(r.header("Idempotent-Replayed")).isEqualTo("true")).hasSize(7);

        var other = w.consult(p, dr.id(), at(tomorrow, "09:30"), at(tomorrow, "10:00"));
        Api.Response reused = desk.post("/v1/appointments", other, key("same-key")).expect(422);
        assertThat(reused.text("code")).isEqualTo("idempotency-key-reused");
        assertThat(jdbc.queryForObject("select count(*) from appointment where patient_id = ?", Integer.class, p))
                .isEqualTo(1);
    }

    @Test
    void aFailedBookingStoresNoKeySoTheRetrySucceedsOnceThereIsRoom() {
        World w = world();
        World.Person dr = w.doctor("Dr Test Three");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "09:15", 15, 1);
        UUID first = w.patient().id();
        UUID second = w.patient().id();
        Instant from = at(tomorrow, "09:00");
        Instant to = at(tomorrow, "09:15");
        String held = desk.post("/v1/appointments", w.consult(first, dr.id(), from, to), key("a")).expect(201).text("id");
        desk.post("/v1/appointments", w.consult(second, dr.id(), from, to), key("b")).expect(409);
        desk.post("/v1/appointments/" + held + "/cancel", Map.of("reason", "synthetic test")).expect(200);
        desk.post("/v1/appointments", w.consult(second, dr.id(), from, to), key("b")).expect(201);
    }

    @Test
    void aPatientCannotHoldTwoOverlappingAppointmentsEvenWhenRacing() {
        World w = world();
        World.Person a = w.doctor("Dr Test Four");
        World.Person b = w.doctor("Dr Test Five");
        w.template(a.id(), "FREQ=DAILY", "09:00", "10:00", 15, 1);
        w.template(b.id(), "FREQ=DAILY", "09:00", "10:00", 15, 1);
        UUID p = w.patient().id();
        Instant from = at(tomorrow, "09:00");
        Instant to = at(tomorrow, "09:15");
        List<UUID> doctors = List.of(a.id(), b.id());

        List<Api.Response> r = Race.run(10, i -> desk.post("/v1/appointments",
                w.consult(p, doctors.get(i % 2), from, to), key("busy-" + i)));

        assertThat(r).filteredOn(x -> x.status() == 201).hasSize(1);
        assertThat(r).filteredOn(x -> x.status() == 409)
                .allSatisfy(x -> assertThat(x.text("code")).isIn("patient-busy", "no-capacity"));
        assertThat(jdbc.queryForObject("select coalesce(sum(booked), 0) from slot where resource_id in (?, ?)",
                Integer.class, a.id(), b.id())).isEqualTo(1);
    }

    @Test
    void multiResourceBookingsTakingRoomsInOppositeOrderNeitherDeadlockNorOverbook() {
        World w = world();
        World.Person dr1 = w.doctor("Dr Proc One");
        World.Person dr2 = w.doctor("Dr Proc Two");
        UUID roomA = w.room("Procedure Room A");
        UUID roomB = w.room("Procedure Room B");
        for (UUID r : List.of(dr1.id(), dr2.id(), roomA, roomB)) {
            w.template(r, "FREQ=DAILY", "09:00", "10:00", 30, 1);
        }
        int n = 24;
        List<UUID> patients = IntStream.range(0, n).mapToObj(i -> w.patient().id()).toList();
        Instant from = at(tomorrow, "09:00");
        Instant to = at(tomorrow, "10:00");

        List<Api.Response> r = Race.run(n, i -> {
            List<UUID> rooms = i % 2 == 0 ? List.of(roomA, roomB) : List.of(roomB, roomA);
            UUID dr = i % 2 == 0 ? dr1.id() : dr2.id();
            return desk.post("/v1/appointments", w.procedure(patients.get(i), dr, from, to, List.of(rooms)),
                    key("proc-" + i));
        });

        // two doctors x two 30-minute slots, but only two rooms x two slots: at most 4 procedures
        long ok = r.stream().filter(x -> x.status() == 201).count();
        assertThat(ok).isEqualTo(4);
        assertThat(r).allSatisfy(x -> assertThat(x.status()).isIn(201, 409));
        Integer maxBooked = jdbc.queryForObject("select max(booked) from slot where resource_id in (?, ?, ?, ?)",
                Integer.class, dr1.id(), dr2.id(), roomA, roomB);
        assertThat(maxBooked).isEqualTo(1);
    }

    @Test
    void rescheduleThatFindsNothingKeepsTheOriginalAppointment() {
        World w = world();
        World.Person dr = w.doctor("Dr Test Six");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "09:30", 15, 1);
        UUID p = w.patient().id();
        UUID q = w.patient().id();
        String appt = desk.post("/v1/appointments", w.consult(p, dr.id(), at(tomorrow, "09:00"), at(tomorrow, "09:15")),
                key("orig")).expect(201).text("id");
        desk.post("/v1/appointments", w.consult(q, dr.id(), at(tomorrow, "09:15"), at(tomorrow, "09:30")), key("q"))
                .expect(201);

        desk.post("/v1/appointments/" + appt + "/reschedule",
                Map.of("from", at(tomorrow, "09:15").toString(), "to", at(tomorrow, "09:30").toString()),
                key("move-1")).expect(409);
        assertThat(desk.get("/v1/appointments/" + appt).expect(200).text("status")).isEqualTo("BOOKED");

        LocalDate later = tomorrow.plusDays(1);
        Api.Response moved = desk.post("/v1/appointments/" + appt + "/reschedule",
                Map.of("from", at(later, "09:00").toString(), "to", at(later, "09:30").toString()), key("move-2"))
                .expect(201);
        assertThat(moved.text("rescheduledFrom")).isEqualTo(appt);
        assertThat(desk.get("/v1/appointments/" + appt).expect(200).text("status")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select booked from slot where resource_id = ? and lower(period) = ?",
                Integer.class, dr.id(), java.sql.Timestamp.from(at(tomorrow, "09:00")))).isZero();
    }

    @Test
    void patientsBookOnlyForThemselvesAndDoctorsSeeOnlyTheirOwnAppointments() {
        World w = world();
        World.Person dr = w.doctor("Dr Test Seven");
        World.Person other = w.doctor("Dr Test Eight");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "10:00", 15, 1);
        World.Person me = w.patient();
        World.Person someoneElse = w.patient();
        var mine = w.consult(me.id(), dr.id(), at(tomorrow, "09:00"), at(tomorrow, "10:00"));
        String id = me.api().post("/v1/appointments", mine, key("self")).expect(201).text("id");
        me.api().post("/v1/appointments", w.consult(someoneElse.id(), dr.id(), at(tomorrow, "09:00"),
                at(tomorrow, "10:00")), key("other")).expect(403);
        someoneElse.api().get("/v1/appointments/" + id).expect(403);
        dr.api().get("/v1/appointments/" + id).expect(200);
        other.api().get("/v1/appointments/" + id).expect(403);
        anon.get("/v1/appointments/" + id).expect(401);
        desk.post("/v1/appointments", mine, Map.of()).expect(400);
    }

    @Test
    void overbookingOpensOnlyOnceTheClinicHasEnoughHistory() {
        World w = world();
        World.Person dr = w.doctor("Dr Test Nine");
        w.template(dr.id(), "FREQ=DAILY", "09:00", "09:15", 15, 4);
        Instant from = at(tomorrow, "09:00");
        Instant to = at(tomorrow, "09:15");
        List<Api.Response> before = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            before.add(desk.post("/v1/appointments", w.consult(w.patient().id(), dr.id(), from, to), key("b" + i)));
        }
        assertThat(before).filteredOn(r -> r.status() == 201).hasSize(4);

        admin.post("/v1/clinics/" + w.clinic + "/attendance-history", Map.of("attended", 700, "noShows", 300))
                .expect(200);
        List<Api.Response> after = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            after.add(desk.post("/v1/appointments", w.consult(w.patient().id(), dr.id(), from, to), key("a" + i)));
        }
        long extra = after.stream().filter(r -> r.status() == 201).count();
        assertThat(extra).isPositive().isLessThanOrEqualTo(2); // max-extra-fraction 0.5 of capacity 4
        assertThat(after).filteredOn(r -> r.status() == 201)
                .allSatisfy(r -> assertThat(r.body().path("overbooked").asBoolean()).isTrue());
    }
}
