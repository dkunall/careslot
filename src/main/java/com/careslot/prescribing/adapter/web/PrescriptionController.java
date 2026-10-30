package com.careslot.prescribing.adapter.web;

import com.careslot.prescribing.application.Prescription;
import com.careslot.prescribing.application.PrescriptionService;
import com.careslot.prescribing.domain.PrescriptionItem;
import com.careslot.shared.security.Actors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
class PrescriptionController {

    record ItemBody(@NotNull String drugCode, @NotNull String dose, @NotNull String frequency, int days) {
    }

    record DraftBody(@NotNull UUID patientId, UUID appointmentId, @NotEmpty @Size(max = 20) List<@Valid ItemBody> items) {
    }

    record SignBody(@Size(max = 500) String overrideReason) {
    }

    record DemoRecordsBody(@NotNull @Size(max = 50) Set<String> medications,
                           @NotNull @Size(max = 50) Set<@Size(max = 60) String> allergies) {
    }

    record RxJson(UUID id, UUID patientId, UUID appointmentId, String prescriber, String status, JsonNode safety,
                  String overrideReason, String contentSha256, Instant signedAt, List<PrescriptionItem> items) {
    }

    private final PrescriptionService rx;
    private final ObjectMapper json;

    PrescriptionController(PrescriptionService rx, ObjectMapper json) {
        this.rx = rx;
        this.json = json;
    }

    private RxJson view(Prescription p) {
        try {
            return new RxJson(p.id(), p.patientId(), p.appointmentId(), p.prescriber(), p.status(),
                    json.readTree(p.safetyJson()), p.overrideReason(), p.contentSha256(), p.signedAt(), p.items());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @GetMapping("/v1/drug-catalog")
    Map<String, Object> catalog() {
        Actors.current();
        return rx.catalogInfo();
    }

    @PostMapping("/v1/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    RxJson draft(@Valid @RequestBody DraftBody body) {
        List<PrescriptionItem> items = body.items().stream()
                .map(i -> new PrescriptionItem(i.drugCode(), i.dose(), i.frequency(), i.days())).toList();
        return view(rx.draft(Actors.current(), body.patientId(), body.appointmentId(), items));
    }

    @PostMapping("/v1/prescriptions/{id}/sign")
    ResponseEntity<RxJson> sign(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                @Valid @RequestBody(required = false) SignBody body) {
        PrescriptionService.Signed s = rx.sign(Actors.current(), id, key, body == null ? null : body.overrideReason());
        return ResponseEntity.ok().header("Idempotent-Replayed", String.valueOf(s.replayed())).body(view(s.prescription()));
    }

    @GetMapping("/v1/prescriptions/{id}")
    RxJson read(@PathVariable UUID id) {
        return view(rx.read(Actors.current(), id));
    }

    @GetMapping("/v1/prescriptions/{id}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"rx-" + id + ".pdf\"")
                .header("Cache-Control", "no-store")
                .body(rx.pdf(Actors.current(), id));
    }

    @PutMapping("/v1/patients/{id}/demo-records")
    ResponseEntity<Void> demoRecords(@PathVariable UUID id, @Valid @RequestBody DemoRecordsBody body) {
        rx.setDemoFacts(Actors.current(), id, body.medications(), body.allergies());
        return ResponseEntity.noContent().build();
    }
}
