package com.careslot.prescribing.application;

import com.careslot.directory.application.DirectoryService;
import com.careslot.directory.application.PatientView;
import com.careslot.prescribing.domain.DrugCatalog;
import com.careslot.prescribing.domain.InteractionChecker;
import com.careslot.prescribing.domain.PatientContext;
import com.careslot.prescribing.domain.PrescriptionBuilder;
import com.careslot.prescribing.domain.PrescriptionItem;
import com.careslot.prescribing.domain.SafetyResult;
import com.careslot.prescribing.domain.SafetyRule;
import com.careslot.prescribing.domain.SignDecision;
import com.careslot.scheduling.application.AppointmentRow;
import com.careslot.scheduling.application.BookingService;
import com.careslot.shared.audit.PhiAudit;
import com.careslot.shared.error.ApiException;
import com.careslot.shared.security.Actor;
import com.careslot.shared.security.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Draft, check and sign prescriptions. Safety checks run against the patient's current medicines and allergies
 * from {@link ClinicalRecordsPort}, again at signing time because records may have changed since the draft.
 * If the records cannot be read the result is "unchecked" and signing fails closed unless overridden with a
 * reason. SAMPLE drug data, not clinically validated.
 */
@Service
public class PrescriptionService {

    public record Signed(Prescription prescription, boolean replayed) {
    }

    private final PrescriptionStore store;
    private final Catalog catalog;
    private final ClinicalRecordsPort records;
    private final Optional<DemoFacts> demoFacts;
    private final DirectoryService directory;
    private final BookingService booking;
    private final PhiAudit audit;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final InteractionChecker checker = new InteractionChecker(SafetyRule.standard());

    PrescriptionService(PrescriptionStore store, Catalog catalog, ClinicalRecordsPort records,
                        ObjectProvider<DemoFacts> demoFacts, DirectoryService directory, BookingService booking,
                        PhiAudit audit, TransactionTemplate tx, ObjectMapper json, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.catalog = catalog;
        this.records = records;
        this.demoFacts = Optional.ofNullable(demoFacts.getIfAvailable());
        this.directory = directory;
        this.booking = booking;
        this.audit = audit;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
    }

    public Prescription draft(Actor actor, UUID patientId, UUID appointmentId, List<PrescriptionItem> items) {
        requireDoctor(actor);
        PatientView patient = directory.patientInternal(patientId).orElseThrow(() -> ApiException.notFound("patient"));
        if (appointmentId != null) {
            AppointmentRow a = booking.get(actor, appointmentId);
            if (!a.patientId().equals(patientId)) {
                throw ApiException.invalid("the appointment is for another patient");
            }
        }
        DrugCatalog c = catalog.get();
        PrescriptionBuilder b = new PrescriptionBuilder(c).patient(patientId).appointment(appointmentId)
                .prescriber(actor.subject());
        items.forEach(b::item);
        PrescriptionBuilder.Draft d = b.build();
        SafetyResult safety = check(patient, d.items(), c);
        Prescription p = new Prescription(UUID.randomUUID(), patientId, appointmentId, actor.subject(), "DRAFT",
                write(safety), null, null, null, null, clock.instant(), d.items());
        tx.executeWithoutResult(s -> {
            store.insertDraft(p);
            audit.record(actor, patientId, "prescription.draft");
        });
        return store.find(p.id()).orElseThrow();
    }

    /**
     * Idempotent on {@code signKey}: the same key returns the stored signature; a second, different
     * key on a signed prescription is a conflict. The row lock means concurrent signs produce one signature.
     */
    public Signed sign(Actor actor, UUID id, String signKey, String overrideReason) {
        requireDoctor(actor);
        if (signKey == null || signKey.isBlank() || signKey.length() > 100) {
            throw ApiException.invalid("Idempotency-Key header (1..100 chars) is required to sign");
        }
        Prescription before = store.find(id).orElseThrow(() -> ApiException.notFound("prescription"));
        if (!before.prescriber().equals(actor.subject())) {
            throw ApiException.forbidden("forbidden", "only the prescriber signs");
        }
        // read the records outside the transaction: a slow record system must not hold the row lock
        SafetyResult safety = before.signed() ? null
                : check(directory.patientInternal(before.patientId()).orElseThrow(), before.items(), catalog.get());
        try {
            Signed result = tx.execute(s -> {
                Prescription p = store.lock(id).orElseThrow();
                if (p.signed()) {
                    if (signKey.equals(p.signKey())) {
                        return new Signed(p, true);
                    }
                    throw ApiException.conflict("already-signed", "this prescription is already signed");
                }
                SignDecision decision = SignDecision.decide(safety, overrideReason);
                meters.counter("careslot.rx.sign", "decision", decision.name()).increment();
                if (!decision.allowed()) {
                    throw new ApiException(422, decision.name().toLowerCase().replace('_', '-'),
                            "signing refused: " + decision, Map.of("safety", safety));
                }
                Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
                String reason = decision == SignDecision.ALLOWED_WITH_OVERRIDE ? overrideReason.strip() : null;
                String sha = RxContent.sha256(RxContent.canonical(p.id(), p.patientId(), p.prescriber(), at, p.items(),
                        reason));
                store.markSigned(id, signKey, reason, sha, at, write(safety));
                audit.record(actor, p.patientId(), reason == null ? "prescription.sign" : "prescription.sign-override");
                return new Signed(store.find(id).orElseThrow(), false);
            });
            return result;
        } catch (DuplicateKeyException e) {
            throw new ApiException(422, "idempotency-key-reused", "this key already signed another prescription");
        }
    }

    public Prescription read(Actor actor, UUID id) {
        Prescription p = store.find(id).orElseThrow(() -> ApiException.notFound("prescription"));
        authoriseRead(actor, p);
        tx.executeWithoutResult(s -> audit.record(actor, p.patientId(), "prescription.read"));
        return p;
    }

    public byte[] pdf(Actor actor, UUID id) {
        Prescription p = store.find(id).orElseThrow(() -> ApiException.notFound("prescription"));
        authoriseRead(actor, p);
        ZoneId zone = p.appointmentId() == null ? ZoneId.of("UTC")
                : directory.zoneOf(booking.get(actor, p.appointmentId()).practitionerId());
        if (!p.signed()) {
            throw ApiException.conflict("not-signed", "only signed prescriptions are printed");
        }
        PatientView patient = directory.patientInternal(p.patientId()).orElseThrow();
        DrugCatalog c = catalog.get();
        List<String> lines = new ArrayList<>(List.of(
                "CareSlot e-prescription",
                "SAMPLE DEMO OUTPUT - drug data is not clinically validated; not for real prescribing.",
                "",
                "Prescription: " + p.id(),
                "Patient: " + patient.name() + "   Health ID: " + patient.healthId(),
                "Prescriber: " + p.prescriber(),
                "Signed: " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz").format(p.signedAt().atZone(zone)),
                ""));
        int n = 1;
        for (PrescriptionItem i : p.items()) {
            String name = c.drug(i.drugCode()).map(d -> d.name()).orElse(i.drugCode());
            lines.add(n++ + ". " + name + " (" + i.drugCode() + ")  " + i.dose() + "  " + i.frequency() + "  x "
                    + i.days() + " days");
        }
        lines.add("");
        if (p.overrideReason() != null) {
            lines.add("Safety override: " + p.overrideReason());
        }
        lines.add("Content SHA-256: " + p.contentSha256());
        tx.executeWithoutResult(s -> audit.record(actor, p.patientId(), "prescription.pdf"));
        return RxPdf.render(lines);
    }

    /** Demo mode only: set the stand-in record of a (synthetic) patient's medicines and allergies. */
    public void setDemoFacts(Actor actor, UUID patientId, Set<String> medications, Set<String> allergies) {
        if (!actor.hasAny(Role.ADMIN, Role.DOCTOR)) {
            throw ApiException.forbidden("forbidden", "admin or doctor");
        }
        DemoFacts facts = demoFacts.orElseThrow(() -> ApiException.conflict("not-demo-mode",
                "records come from the FHIR server in this deployment"));
        directory.patientInternal(patientId).orElseThrow(() -> ApiException.notFound("patient"));
        DrugCatalog c = catalog.get();
        for (String m : medications) {
            if (c.drug(m).isEmpty()) {
                throw ApiException.invalid("unknown drug code " + m);
            }
        }
        tx.executeWithoutResult(s -> {
            facts.replace(patientId, medications, allergies);
            audit.record(actor, patientId, "records.demo-write");
        });
    }

    public Map<String, Object> catalogInfo() {
        DrugCatalog c = catalog.get();
        return Map.of("drugs", c.codes().size(), "interactions", c.interactionCount(),
                "note", "SAMPLE data, not clinically validated");
    }

    private SafetyResult check(PatientView patient, List<PrescriptionItem> items, DrugCatalog c) {
        PatientContext ctx;
        try {
            ctx = records.read(patient.id(), patient.healthId());
        } catch (ClinicalRecordsPort.RecordsUnavailableException e) {
            meters.counter("careslot.rx.records", "result", "unavailable").increment();
            return SafetyResult.unchecked("records unavailable: " + e.getMessage());
        }
        return checker.check(items, ctx, c);
    }

    private void authoriseRead(Actor actor, Prescription p) {
        if (actor.has(Role.DOCTOR)) {
            if (p.prescriber().equals(actor.subject())) {
                return;
            }
            throw ApiException.forbidden("forbidden", "doctors see the prescriptions they wrote");
        }
        directory.authorisePatientAccess(actor, p.patientId());
    }

    private static void requireDoctor(Actor actor) {
        if (!actor.has(Role.DOCTOR)) {
            throw ApiException.forbidden("forbidden", "doctors only");
        }
    }

    private String write(SafetyResult r) {
        try {
            return json.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
