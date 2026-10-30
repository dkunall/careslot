package com.careslot.it;

import com.careslot.support.Api;
import com.careslot.support.ItBase;
import com.careslot.support.Race;
import com.careslot.support.World;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.careslot.support.World.key;
import static org.assertj.core.api.Assertions.assertThat;

class PrescriptionIT extends ItBase {

    private static Map<String, Object> item(String code) {
        return Map.of("drugCode", code, "dose", "1 tab", "frequency", "OD", "days", 5);
    }

    private static Map<String, Object> draft(UUID patient, String... codes) {
        return Map.of("patientId", patient, "items", List.of(codes).stream().map(PrescriptionIT::item).toList());
    }

    @Test
    void severeInteractionBlocksSigningUntilAReasonedOverride() {
        World w = world();
        World.Person dr = w.doctor("Dr Rx Severe");
        World.Person pt = w.patient();
        dr.api().put("/v1/patients/" + pt.id() + "/demo-records",
                Map.of("medications", Set.of("RX-WARF"), "allergies", Set.of())).expect(204);

        Api.Response d = dr.api().post("/v1/prescriptions", draft(pt.id(), "RX-ASPI", "RX-PARA")).expect(201);
        String id = d.text("id");
        assertThat(d.text("status")).isEqualTo("DRAFT");
        assertThat(d.body().path("safety").path("checked").asBoolean()).isTrue();
        assertThat(d.body().path("safety").path("findings").get(0).path("severity").asText()).isEqualTo("SEVERE");

        Api.Response blocked = dr.api().post("/v1/prescriptions/" + id + "/sign", Map.of(), key("s-1")).expect(422);
        assertThat(blocked.text("code")).isEqualTo("blocked-severe");
        assertThat(blocked.body().path("safety").path("findings")).isNotEmpty();
        assertThat(dr.api().post("/v1/prescriptions/" + id + "/sign", Map.of("overrideReason", "too short"),
                key("s-2")).expect(422).text("code")).isEqualTo("override-reason-too-short");
        dr.api().post("/v1/prescriptions/" + id + "/sign", Map.of(), Map.of()).expect(400);

        Api.Response signed = dr.api().post("/v1/prescriptions/" + id + "/sign",
                Map.of("overrideReason", "cardiology advised short course, INR monitored"), key("s-3")).expect(200);
        assertThat(signed.text("status")).isEqualTo("SIGNED");
        assertThat(signed.text("contentSha256")).hasSize(64);
        assertThat(signed.text("overrideReason")).startsWith("cardiology");
        assertThat(jdbc.queryForObject("select count(*) from phi_access where patient_id = ? and action = ?",
                Integer.class, pt.id(), "prescription.sign-override")).isEqualTo(1);
    }

    @Test
    void concurrentSignsWithOneKeyProduceOneSignatureAndOtherKeysConflict() {
        World w = world();
        World.Person dr = w.doctor("Dr Rx Race");
        World.Person pt = w.patient();
        String id = dr.api().post("/v1/prescriptions", draft(pt.id(), "RX-PARA")).expect(201).text("id");

        List<Api.Response> r = Race.run(10, i -> dr.api().post("/v1/prescriptions/" + id + "/sign", Map.of(),
                key("same-key")));
        assertThat(r).allSatisfy(x -> assertThat(x.status()).isEqualTo(200));
        assertThat(r.stream().filter(x -> "false".equals(x.header("Idempotent-Replayed")))).hasSize(1);
        assertThat(r.stream().map(x -> x.text("signedAt")).distinct()).hasSize(1);
        assertThat(r.stream().map(x -> x.text("contentSha256")).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from phi_access where patient_id = ? and action = ?",
                Integer.class, pt.id(), "prescription.sign")).isEqualTo(1);

        assertThat(dr.api().post("/v1/prescriptions/" + id + "/sign", Map.of(), key("other-key")).expect(409)
                .text("code")).isEqualTo("already-signed");

        // a key already used on one prescription cannot sign a second one
        String second = dr.api().post("/v1/prescriptions", draft(pt.id(), "RX-PARA")).expect(201).text("id");
        assertThat(dr.api().post("/v1/prescriptions/" + second + "/sign", Map.of(), key("same-key")).expect(422)
                .text("code")).isEqualTo("idempotency-key-reused");
    }

    @Test
    void allergyClassIsSevereAndReadAccessFollowsThePrescriber() {
        World w = world();
        World.Person dr = w.doctor("Dr Rx Access");
        World.Person other = w.doctor("Dr Rx Other");
        World.Person pt = w.patient();
        World.Person stranger = w.patient();
        nurse.put("/v1/patients/" + pt.id() + "/demo-records",
                Map.of("medications", Set.of(), "allergies", Set.of("class:PENICILLIN"))).expect(403);
        admin.put("/v1/patients/" + pt.id() + "/demo-records",
                Map.of("medications", Set.of(), "allergies", Set.of("class:PENICILLIN"))).expect(204);

        nurse.post("/v1/prescriptions", draft(pt.id(), "RX-PARA")).expect(403);
        Api.Response d = dr.api().post("/v1/prescriptions", draft(pt.id(), "RX-AMOX")).expect(201);
        assertThat(d.body().path("safety").path("findings").get(0).path("severity").asText()).isEqualTo("SEVERE");
        String id = d.text("id");
        other.api().post("/v1/prescriptions/" + id + "/sign", Map.of(), key("o-1")).expect(403);

        String ok = dr.api().post("/v1/prescriptions", draft(pt.id(), "RX-PARA")).expect(201).text("id");
        dr.api().get("/v1/prescriptions/" + ok + "/pdf").expect(409);
        dr.api().post("/v1/prescriptions/" + ok + "/sign", Map.of(), key("ok-1")).expect(200);

        other.api().get("/v1/prescriptions/" + ok).expect(403);
        stranger.api().get("/v1/prescriptions/" + ok).expect(403);
        pt.api().get("/v1/prescriptions/" + ok).expect(200);
        nurse.get("/v1/prescriptions/" + ok).expect(200);

        Api.Response pdf = pt.api().get("/v1/prescriptions/" + ok + "/pdf").expect(200);
        assertThat(pdf.header("Content-Type")).startsWith("application/pdf");
        assertThat(pdf.body().asText()).startsWith("%PDF-1.4").contains("SAMPLE DEMO OUTPUT").contains("Paracetamol");
        assertThat(jdbc.queryForObject("select count(*) from phi_access where patient_id = ? and action = ?",
                Integer.class, pt.id(), "prescription.pdf")).isEqualTo(1);
    }

    @Test
    void draftAgainstAnotherDoctorsAppointmentIsRefused() {
        World w = world();
        World.Person dr = w.doctor("Dr Rx Appt");
        World.Person other = w.doctor("Dr Rx Appt Other");
        World.Person pt = w.patient();
        w.template(dr.id(), "FREQ=DAILY", "00:00", "23:55", 5, 5);
        Instant start = null;
        for (var day : List.of(World.today(), World.today().plusDays(1))) {
            var starts = desk.get("/v1/practitioners/" + dr.id() + "/availability?date=" + day).expect(200).body()
                    .path("starts");
            if (!starts.isEmpty()) {
                start = Instant.parse(starts.get(0).asText());
                break;
            }
        }
        String appt = desk.post("/v1/appointments", w.consult(pt.id(), dr.id(), start, start.plusSeconds(300)),
                key("rx-appt")).expect(201).text("id");
        Map<String, Object> body = Map.of("patientId", pt.id(), "appointmentId", appt, "items", List.of(item("RX-PARA")));
        other.api().post("/v1/prescriptions", body).expect(403);
        assertThat(dr.api().post("/v1/prescriptions", body).expect(201).text("appointmentId")).isEqualTo(appt);
        dr.api().post("/v1/prescriptions", Map.of("patientId", w.patient().id(), "appointmentId", appt,
                "items", List.of(item("RX-PARA")))).expect(400);
        dr.api().post("/v1/prescriptions", draft(pt.id(), "RX-NOPE")).expect(400);
    }
}
