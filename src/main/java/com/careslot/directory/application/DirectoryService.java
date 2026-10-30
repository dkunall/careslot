package com.careslot.directory.application;

import com.careslot.shared.domain.ResourceKind;
import com.careslot.shared.audit.PhiAudit;
import com.careslot.shared.crypto.PhiCipher;
import com.careslot.shared.error.ApiException;
import com.careslot.shared.security.Actor;
import com.careslot.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class DirectoryService {

    private final DirectoryStore store;
    private final PhiCipher cipher;
    private final PhiAudit audit;

    DirectoryService(DirectoryStore store, PhiCipher cipher, PhiAudit audit) {
        this.store = store;
        this.cipher = cipher;
        this.audit = audit;
    }

    public void createFacility(Actor actor, String id, String name, String zone) {
        requireAdmin(actor);
        ZoneId z;
        try {
            z = ZoneId.of(zone);
        } catch (RuntimeException e) {
            throw ApiException.invalid("unknown time zone " + zone);
        }
        if (!store.insertFacility(new Facility(id, name, z))) {
            throw ApiException.conflict("duplicate-facility", "facility " + id + " exists");
        }
    }

    public void createClinic(Actor actor, String id, String facilityId, String name) {
        requireAdmin(actor);
        facility(facilityId);
        if (!store.insertClinic(new Clinic(id, facilityId, name))) {
            throw ApiException.conflict("duplicate-clinic", "clinic " + id + " exists");
        }
    }

    @Transactional
    public UUID createResource(Actor actor, String facilityId, String clinicId, ResourceKind kind, String name,
                               String loginSubject) {
        requireAdmin(actor);
        facility(facilityId);
        if (kind == ResourceKind.PRACTITIONER && clinicId == null) {
            throw ApiException.invalid("a practitioner belongs to a clinic");
        }
        if (clinicId != null && !clinic(clinicId).facilityId().equals(facilityId)) {
            throw ApiException.invalid("clinic is in another facility");
        }
        UUID id = UUID.randomUUID();
        store.insertResource(new Resource(id, facilityId, clinicId, kind, name, loginSubject));
        return id;
    }

    public void addHoliday(Actor actor, String facilityId, LocalDate day, String name) {
        requireAdmin(actor);
        facility(facilityId);
        if (!store.addHoliday(facilityId, day, name)) {
            throw ApiException.conflict("duplicate-holiday", day + " is already a holiday");
        }
    }

    /** Seeds the no-show estimate with history from the old system; live outcomes add to it afterwards. */
    public void importAttendance(Actor actor, String clinicId, long attended, long noShows) {
        requireAdmin(actor);
        clinic(clinicId);
        if (attended < 0 || noShows < 0) {
            throw ApiException.invalid("counts must be >= 0");
        }
        store.addAttendanceHistory(clinicId, attended, noShows);
    }

    @Transactional
    public UUID registerPatient(Actor actor, String healthId, String name, String phone, String email,
                                Integer birthYear, String loginSubject) {
        if (!actor.hasAny(Role.FRONT_DESK, Role.ADMIN)) {
            throw ApiException.forbidden("forbidden", "front desk registers patients");
        }
        String hid = HealthIds.normalise(healthId);
        if (name == null || name.isBlank()) {
            throw ApiException.invalid("name is required");
        }
        byte[] hash = cipher.blindIndex(hid);
        UUID id = UUID.randomUUID();
        Optional<UUID> inserted = store.insertPatient(new DirectoryStore.EncryptedPatient(id, hash,
                cipher.encrypt(hid, "patient.health_id"), cipher.encrypt(name.strip(), "patient.name"),
                cipher.encrypt(phone, "patient.phone"), cipher.encrypt(email, "patient.email"), birthYear,
                loginSubject));
        if (inserted.isEmpty()) {
            UUID existing = store.patientIdByHealthIdHash(hash).orElseThrow();
            throw new ApiException(409, "duplicate-health-id", "this health ID is already registered",
                    Map.of("patientId", existing.toString()));
        }
        audit.record(actor, id, "patient.register");
        return id;
    }

    @Transactional
    public PatientView readPatient(Actor actor, UUID patientId) {
        authorisePatientAccess(actor, patientId);
        DirectoryStore.EncryptedPatient p = store.patient(patientId).orElseThrow(() -> ApiException.notFound("patient"));
        audit.record(actor, patientId, "patient.read");
        return decrypt(p);
    }

    @Transactional
    public UUID findByHealthId(Actor actor, String healthId) {
        if (!actor.staff()) {
            throw ApiException.forbidden("forbidden", "staff only");
        }
        UUID id = store.patientIdByHealthIdHash(cipher.blindIndex(HealthIds.normalise(healthId)))
                .orElseThrow(() -> ApiException.notFound("patient"));
        audit.record(actor, id, "patient.lookup");
        return id;
    }

    /** For internal callers (reminders, FHIR, records adapter) that log their own access. */
    public Optional<PatientView> patientInternal(UUID id) {
        return store.patient(id).map(this::decrypt);
    }

    /** Staff may see any patient; a patient only herself. */
    public void authorisePatientAccess(Actor actor, UUID patientId) {
        if (actor.staff()) {
            return;
        }
        if (actor.has(Role.PATIENT) && store.patientIdForSubject(actor.subject()).filter(patientId::equals).isPresent()) {
            return;
        }
        throw ApiException.forbidden("forbidden", "not your record");
    }

    public Optional<UUID> patientIdFor(Actor actor) {
        return store.patientIdForSubject(actor.subject());
    }

    public Optional<UUID> practitionerIdFor(Actor actor) {
        return store.practitionerIdForSubject(actor.subject());
    }

    public Facility facility(String id) {
        return store.facility(id).orElseThrow(() -> ApiException.notFound("facility " + id));
    }

    public Clinic clinic(String id) {
        return store.clinic(id).orElseThrow(() -> ApiException.notFound("clinic " + id));
    }

    public Resource resource(UUID id) {
        return store.resource(id).orElseThrow(() -> ApiException.notFound("resource " + id));
    }

    public List<Resource> practitioners(String clinicId) {
        return store.practitioners(clinicId);
    }

    public Set<LocalDate> holidays(String facilityId, LocalDate from, LocalDate to) {
        return store.holidays(facilityId, from, to);
    }

    public Optional<ZoneId> practitionerZone(UUID resourceId) {
        return store.resource(resourceId).flatMap(r -> store.facility(r.facilityId())).map(Facility::zone);
    }

    public ZoneId zoneOf(UUID resourceId) {
        return facility(resource(resourceId).facilityId()).zone();
    }

    private PatientView decrypt(DirectoryStore.EncryptedPatient p) {
        return new PatientView(p.id(), cipher.decrypt(p.healthId(), "patient.health_id"),
                cipher.decrypt(p.name(), "patient.name"), cipher.decrypt(p.phone(), "patient.phone"),
                cipher.decrypt(p.email(), "patient.email"), p.birthYear());
    }

    private static void requireAdmin(Actor actor) {
        if (!actor.has(Role.ADMIN)) {
            throw ApiException.forbidden("forbidden", "admin only");
        }
    }
}
