package com.careslot.support;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A fresh synthetic facility per test: its own clinic, doctors, rooms and patients, all made through the API. */
public final class World {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final SecureRandom RANDOM = new SecureRandom();

    public final String facility;
    public final String clinic;
    private final ItBase it;

    World(ItBase it) {
        this.it = it;
        String tag = Long.toHexString(RANDOM.nextLong() & 0xffffffffffL);
        this.facility = "fac-" + tag;
        this.clinic = "cli-" + tag;
        it.admin.post("/v1/facilities", Map.of("id", facility, "name", "Synthetic Hospital " + tag, "zone", ZONE.getId()))
                .expect(201);
        it.admin.post("/v1/clinics", Map.of("id", clinic, "facilityId", facility, "name", "General Medicine")).expect(201);
    }

    public record Person(UUID id, String subject, Api api) {
    }

    public Person doctor(String name) {
        String subject = "dr-" + UUID.randomUUID().toString().substring(0, 8) + "@careslot.test";
        UUID id = UUID.fromString(it.admin.post("/v1/resources", Map.of("facilityId", facility, "clinicId", clinic,
                "kind", "PRACTITIONER", "name", name, "loginSubject", subject)).expect(201).text("id"));
        return new Person(id, subject, it.anon.as(TestJwt.doctor(subject)));
    }

    public UUID room(String name) {
        return UUID.fromString(it.admin.post("/v1/resources", Map.of("facilityId", facility, "kind", "ROOM",
                "name", name)).expect(201).text("id"));
    }

    public Person patient() {
        String subject = "pt-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        StringBuilder hid = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            hid.append(RANDOM.nextInt(10));
        }
        UUID id = UUID.fromString(it.desk.post("/v1/patients", Map.of("healthId", hid.toString(),
                "name", "Test Patient " + subject.substring(3, 7), "phone", "+910000000000",
                "email", subject, "birthYear", 1990, "loginSubject", subject)).expect(201).text("id"));
        return new Person(id, subject, it.anon.as(TestJwt.patient(subject)));
    }

    public String template(UUID resource, String rrule, String start, String end, int minutes, int capacity) {
        return it.admin.post("/v1/resources/" + resource + "/templates", Map.of("rrule", rrule, "dayStart", start,
                "dayEnd", end, "slotMinutes", minutes, "capacity", capacity,
                "validFrom", today().toString())).expect(201).text("templateId");
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
    }

    public Map<String, Object> consult(UUID patient, UUID doctor, Instant from, Instant to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patientId", patient);
        m.put("clinicId", clinic);
        m.put("kind", "CONSULT");
        if (doctor != null) {
            m.put("practitionerId", doctor);
        }
        m.put("from", from.toString());
        m.put("to", to.toString());
        return m;
    }

    public Map<String, Object> procedure(UUID patient, UUID doctor, Instant from, Instant to, List<List<UUID>> needs) {
        Map<String, Object> m = consult(patient, doctor, from, to);
        m.put("kind", "PROCEDURE");
        m.put("needs", needs);
        return m;
    }

    public static Map<String, String> key(String k) {
        return Map.of("Idempotency-Key", k);
    }
}
