package com.careslot.prescribing.adapter.records;

import com.careslot.prescribing.application.ClinicalRecordsPort;
import com.careslot.prescribing.domain.PatientContext;
import com.careslot.shared.config.CareSlotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Reads active MedicationStatement and AllergyIntolerance resources from a FHIR R4 server, by the patient's
 * health ID. Codes are taken from codings in {@link #DRUG_SYSTEM} (and {@link #CLASS_SYSTEM} for class
 * allergies); anything else is ignored. Any error, non-200 or timeout is "unavailable", which the caller turns
 * into a fail-closed unchecked result. Only the first page of each search is read.
 */
@Component
@ConditionalOnProperty(name = "careslot.records.mode", havingValue = "fhir")
public class FhirRecordsAdapter implements ClinicalRecordsPort {

    public static final String DRUG_SYSTEM = "urn:careslot:drug";
    public static final String CLASS_SYSTEM = "urn:careslot:drug-class";
    public static final String HEALTH_ID_SYSTEM = "urn:careslot:health-id";

    private final HttpClient http;
    private final ObjectMapper json;
    private final String base;
    private final Duration timeout;

    @Autowired
    FhirRecordsAdapter(CareSlotProperties props, ObjectMapper json) {
        this(props.records().fhirBase(), props.records().timeout(), json);
    }

    public FhirRecordsAdapter(String base, Duration timeout, ObjectMapper json) {
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("careslot.records.fhir-base is required in fhir mode");
        }
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.timeout = timeout;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public PatientContext read(UUID patientId, String healthId) {
        String who = URLEncoder.encode(HEALTH_ID_SYSTEM + "|" + healthId, StandardCharsets.UTF_8);
        Set<String> meds = new HashSet<>();
        for (JsonNode r : search("MedicationStatement?status=active&patient.identifier=" + who)) {
            codes(r.path("medicationCodeableConcept"), DRUG_SYSTEM, "", meds);
        }
        Set<String> allergies = new HashSet<>();
        for (JsonNode r : search("AllergyIntolerance?clinical-status=active&patient.identifier=" + who)) {
            codes(r.path("code"), DRUG_SYSTEM, "", allergies);
            codes(r.path("code"), CLASS_SYSTEM, "class:", allergies);
        }
        return new PatientContext(meds, allergies, "fhir");
    }

    private Iterable<JsonNode> search(String query) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + query)).timeout(timeout)
                .header("Accept", "application/fhir+json").GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new RecordsUnavailableException("FHIR server answered " + res.statusCode(), null);
            }
            JsonNode bundle = json.readTree(res.body());
            if (!"Bundle".equals(bundle.path("resourceType").asText())) {
                throw new RecordsUnavailableException("FHIR server did not return a Bundle", null);
            }
            return () -> java.util.stream.StreamSupport.stream(bundle.path("entry").spliterator(), false)
                    .map(e -> e.path("resource")).iterator();
        } catch (IOException e) {
            throw new RecordsUnavailableException("FHIR server unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RecordsUnavailableException("interrupted", e);
        }
    }

    private static void codes(JsonNode concept, String system, String prefix, Set<String> out) {
        for (JsonNode c : concept.path("coding")) {
            if (system.equals(c.path("system").asText()) && c.hasNonNull("code")) {
                String code = c.path("code").asText();
                // class codes are stored upper-case in the catalog (class:PENICILLIN)
                out.add(prefix.isEmpty() ? code : prefix + code.toUpperCase(java.util.Locale.ROOT));
            }
        }
    }
}
