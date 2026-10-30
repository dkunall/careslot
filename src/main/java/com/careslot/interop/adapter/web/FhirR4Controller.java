package com.careslot.interop.adapter.web;

import com.careslot.directory.application.DirectoryService;
import com.careslot.directory.application.PatientView;
import com.careslot.prescribing.application.Catalog;
import com.careslot.prescribing.application.Prescription;
import com.careslot.prescribing.application.PrescriptionService;
import com.careslot.prescribing.domain.PrescriptionItem;
import com.careslot.scheduling.application.AppointmentRow;
import com.careslot.scheduling.application.BookingService;
import com.careslot.shared.security.Actor;
import com.careslot.shared.security.Actors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A read-only FHIR R4 view (JSON only) over CareSlot's own data, so other hospital systems can pull a patient,
 * an appointment or a prescription without learning the CareSlot API. Hand-written maps, not a full FHIR
 * server: no search beyond what is listed, no _include, no validation against profiles.
 */
@RestController
@RequestMapping(value = "/fhir/r4", produces = {"application/fhir+json", "application/json"})
class FhirR4Controller {

    static final String HEALTH_ID = "urn:careslot:health-id";
    static final String DRUG = "urn:careslot:drug";
    static final String PRESCRIPTION = "urn:careslot:prescription";

    private final DirectoryService directory;
    private final BookingService booking;
    private final PrescriptionService rx;
    private final Catalog catalog;

    FhirR4Controller(DirectoryService directory, BookingService booking, PrescriptionService rx, Catalog catalog) {
        this.directory = directory;
        this.booking = booking;
        this.rx = rx;
        this.catalog = catalog;
    }

    @GetMapping("/metadata")
    Map<String, Object> metadata() {
        List<Map<String, Object>> resources = List.of(
                Map.of("type", "Patient", "interaction", List.of(Map.of("code", "read"))),
                Map.of("type", "Appointment", "interaction", List.of(Map.of("code", "read"))),
                Map.of("type", "MedicationRequest", "interaction", List.of(Map.of("code", "search-type")),
                        "searchParam", List.of(Map.of("name", "prescription", "type", "token"))));
        return Map.of("resourceType", "CapabilityStatement", "status", "active", "kind", "instance",
                "fhirVersion", "4.0.1", "format", List.of("json"),
                "rest", List.of(Map.of("mode", "server", "resource", resources)));
    }

    @GetMapping("/Patient/{id}")
    Map<String, Object> patient(@PathVariable UUID id) {
        PatientView p = directory.readPatient(Actors.current(), id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "Patient");
        r.put("id", p.id().toString());
        r.put("identifier", List.of(Map.of("system", HEALTH_ID, "value", p.healthId())));
        r.put("name", List.of(Map.of("text", p.name())));
        List<Map<String, Object>> telecom = new ArrayList<>();
        if (p.phone() != null) {
            telecom.add(Map.of("system", "phone", "value", p.phone()));
        }
        if (p.email() != null) {
            telecom.add(Map.of("system", "email", "value", p.email()));
        }
        r.put("telecom", telecom);
        if (p.birthYear() != null) {
            r.put("birthDate", String.format("%04d", p.birthYear()));
        }
        return r;
    }

    @GetMapping("/Appointment/{id}")
    Map<String, Object> appointment(@PathVariable UUID id) {
        AppointmentRow a = booking.get(Actors.current(), id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "Appointment");
        r.put("id", a.id().toString());
        r.put("status", status(a));
        r.put("appointmentType", Map.of("text", a.kind().name()));
        r.put("start", a.period().start().toString());
        r.put("end", a.period().end().toString());
        r.put("participant", List.of(
                Map.of("actor", Map.of("reference", "Patient/" + a.patientId()), "status", "accepted"),
                Map.of("actor", Map.of("reference", "Practitioner/" + a.practitionerId()), "status", "accepted")));
        return r;
    }

    static String status(AppointmentRow a) {
        return switch (a.status()) {
            case REQUESTED -> "pending";
            case BOOKED -> "booked";
            case CHECKED_IN -> "checked-in";
            case IN_CONSULT -> "arrived";
            case COMPLETED -> "fulfilled";
            case CANCELLED -> "cancelled";
            case NO_SHOW -> "noshow";
        };
    }

    /** FHIR has one medication per MedicationRequest, so a prescription becomes a group of them. */
    @GetMapping("/MedicationRequest")
    Map<String, Object> medicationRequests(@RequestParam("prescription") UUID prescriptionId) {
        Actor actor = Actors.current();
        Prescription p = rx.read(actor, prescriptionId);
        List<Map<String, Object>> entries = new ArrayList<>();
        int line = 1;
        for (PrescriptionItem i : p.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resourceType", "MedicationRequest");
            m.put("id", p.id() + "-" + line);
            m.put("status", p.signed() ? "active" : "draft");
            m.put("intent", "order");
            m.put("groupIdentifier", Map.of("system", PRESCRIPTION, "value", p.id().toString()));
            String display = catalog.get().drug(i.drugCode()).map(d -> d.name()).orElse(i.drugCode());
            m.put("medicationCodeableConcept", Map.of("coding", List.of(Map.of("system", DRUG, "code", i.drugCode(),
                    "display", display))));
            m.put("subject", Map.of("reference", "Patient/" + p.patientId()));
            m.put("requester", Map.of("display", p.prescriber()));
            m.put("authoredOn", (p.signed() ? p.signedAt() : p.createdAt()).toString());
            m.put("dosageInstruction", List.of(Map.of("text", i.dose() + " " + i.frequency() + " for " + i.days()
                    + " days")));
            entries.add(Map.of("fullUrl", "MedicationRequest/" + m.get("id"), "resource", m));
            line++;
        }
        return Map.of("resourceType", "Bundle", "type", "searchset", "total", entries.size(), "entry", entries);
    }
}
