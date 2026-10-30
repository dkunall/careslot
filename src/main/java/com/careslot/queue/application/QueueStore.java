package com.careslot.queue.application;

import com.careslot.queue.domain.ConsultDuration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the OPD queue. All methods run inside the caller's transaction. */
public interface QueueStore {

    Optional<QueueToken> forAppointment(UUID appointmentId);

    /** Next number for the doctor's day; the counter row is upserted, so concurrent check-ins serialise on it. */
    int nextNumber(UUID practitionerId, LocalDate day);

    /** Locks the doctor's counter row for the day so two call-next requests cannot both call a patient. */
    boolean lockDay(UUID practitionerId, LocalDate day);

    QueueToken insert(QueueToken token);

    List<QueueToken> day(UUID practitionerId, LocalDate day);

    Optional<QueueToken> called(UUID practitionerId, LocalDate day);

    Optional<QueueToken> firstWaiting(UUID practitionerId, LocalDate day);

    void markCalled(UUID tokenId, Instant at);

    void markDone(UUID tokenId, Instant at);

    /** Locked row, or empty for a doctor with no finished consult yet. */
    Optional<ConsultDuration> lockStats(UUID practitionerId);

    Optional<ConsultDuration> stats(UUID practitionerId);

    void saveStats(UUID practitionerId, ConsultDuration d);

    void saveNote(UUID appointmentId, byte[] encrypted, String author);
}
