package com.careslot.scheduling.application;

import com.careslot.scheduling.domain.Interval;
import com.careslot.scheduling.domain.WaitlistEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistStore {

    record Row(WaitlistEntry entry, String status, UUID appointmentId) {
    }

    /** False if the patient already waits for this doctor. */
    boolean insert(WaitlistEntry e, String createdBy);

    Optional<Row> find(UUID id);

    boolean withdraw(UUID id);

    /** WAITING entries for the doctor whose window covers {@code freed}, locked, skipping rows others hold. */
    List<WaitlistEntry> lockCandidates(UUID practitionerId, Interval freed);

    void markBooked(UUID id, UUID appointmentId);

    List<Row> forPractitioner(UUID practitionerId);
}
