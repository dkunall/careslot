package com.careslot.directory.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence port for facilities, clinics, resources and patients. PHI arrives here already encrypted. */
public interface DirectoryStore {

    record EncryptedPatient(UUID id, byte[] healthIdHash, byte[] healthId, byte[] name, byte[] phone, byte[] email,
                            Integer birthYear, String loginSubject) {
    }

    boolean insertFacility(Facility f);

    boolean insertClinic(Clinic c);

    void insertResource(Resource r);

    Optional<Facility> facility(String id);

    Optional<Clinic> clinic(String id);

    Optional<Resource> resource(UUID id);

    List<Resource> practitioners(String clinicId);

    boolean addHoliday(String facilityId, LocalDate day, String name);

    Set<LocalDate> holidays(String facilityId, LocalDate from, LocalDate to);

    void addAttendanceHistory(String clinicId, long attended, long noShows);

    /** Empty if the health ID is already registered (blind index collision). */
    Optional<UUID> insertPatient(EncryptedPatient p);

    Optional<UUID> patientIdByHealthIdHash(byte[] hash);

    Optional<EncryptedPatient> patient(UUID id);

    Optional<UUID> patientIdForSubject(String subject);

    Optional<UUID> practitionerIdForSubject(String subject);
}
