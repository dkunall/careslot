package com.careslot.it;

import com.careslot.support.Api;
import com.careslot.support.ItBase;
import com.careslot.support.World;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.careslot.support.World.key;
import static org.assertj.core.api.Assertions.assertThat;

class FhirIT extends ItBase {

    @Test
    void patientAppointmentAndPrescriptionReadAsFhirWithTheSameAccessRules() {
        World w = world();
        World.Person dr = w.doctor("Dr Fhir");
        World.Person pt = w.patient();
        World.Person other = w.patient();
        w.template(dr.id(), "FREQ=DAILY", "00:00", "23:55", 5, 5);
        Instant start = null;
        for (var day : List.of(World.today(), World.today().plusDays(1))) {
            JsonNode starts = desk.get("/v1/practitioners/" + dr.id() + "/availability?date=" + day).expect(200).body()
                    .path("starts");
            if (!starts.isEmpty()) {
                start = Instant.parse(starts.get(0).asText());
                break;
            }
        }
        String appt = desk.post("/v1/appointments", w.consult(pt.id(), dr.id(), start, start.plusSeconds(300)),
                key("fhir-1")).expect(201).text("id");

        Api.Response cap = anon.as(com.careslot.support.TestJwt.patient(pt.subject())).get("/fhir/r4/metadata").expect(200);
        assertThat(cap.text("fhirVersion")).isEqualTo("4.0.1");

        Api.Response patient = pt.api().get("/fhir/r4/Patient/" + pt.id()).expect(200);
        assertThat(patient.header("Content-Type")).startsWith("application/fhir+json");
        assertThat(patient.text("resourceType")).isEqualTo("Patient");
        assertThat(patient.body().path("identifier").get(0).path("system").asText()).isEqualTo("urn:careslot:health-id");
        assertThat(patient.body().path("identifier").get(0).path("value").asText()).matches("[0-9-]{14,17}");

        Api.Response denied = other.api().get("/fhir/r4/Patient/" + pt.id()).expect(403);
        assertThat(denied.text("resourceType")).isEqualTo("OperationOutcome");
        assertThat(denied.body().path("issue").get(0).path("code").asText()).isEqualTo("forbidden");
        assertThat(desk.get("/fhir/r4/Patient/" + UUID.randomUUID()).expect(404).text("resourceType"))
                .isEqualTo("OperationOutcome");
        assertThat(desk.get("/fhir/r4/Patient/not-a-uuid").expect(400).body().path("issue").get(0).path("code")
                .asText()).isEqualTo("invalid");

        Api.Response a = dr.api().get("/fhir/r4/Appointment/" + appt).expect(200);
        assertThat(a.text("status")).isEqualTo("booked");
        assertThat(a.body().path("participant").get(0).path("actor").path("reference").asText())
                .isEqualTo("Patient/" + pt.id());
        desk.post("/v1/appointments/" + appt + "/check-in", Map.of()).expect(201);
        assertThat(pt.api().get("/fhir/r4/Appointment/" + appt).expect(200).text("status")).isEqualTo("checked-in");

        String rx = dr.api().post("/v1/prescriptions", Map.of("patientId", pt.id(), "appointmentId", appt,
                "items", List.of(Map.of("drugCode", "RX-PARA", "dose", "500 mg", "frequency", "TDS", "days", 3),
                        Map.of("drugCode", "RX-AMOX", "dose", "500 mg", "frequency", "TDS", "days", 5)))).expect(201)
                .text("id");
        Api.Response bundle = pt.api().get("/fhir/r4/MedicationRequest?prescription=" + rx).expect(200);
        assertThat(bundle.text("type")).isEqualTo("searchset");
        assertThat(bundle.body().path("total").asInt()).isEqualTo(2);
        JsonNode first = bundle.body().path("entry").get(0).path("resource");
        assertThat(first.path("status").asText()).isEqualTo("draft");
        assertThat(first.path("medicationCodeableConcept").path("coding").get(0).path("display").asText())
                .isEqualTo("Paracetamol");
        assertThat(first.path("groupIdentifier").path("value").asText()).isEqualTo(rx);
        assertThat(other.api().get("/fhir/r4/MedicationRequest?prescription=" + rx).expect(403).text("resourceType"))
                .isEqualTo("OperationOutcome");
    }
}
