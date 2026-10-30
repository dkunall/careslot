package com.careslot.scheduling.application;

import com.careslot.scheduling.domain.AppointmentStatus;
import com.careslot.scheduling.domain.Interval;
import com.careslot.scheduling.domain.NoShowEstimate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for appointments, attendance counters and the reminder schedule. */
public interface AppointmentStore {

    /** Serialises requests that share an idempotency key until this transaction ends. */
    void lockIdempotencyKey(String scope, String key);

    Optional<AppointmentRow> byIdempotencyKey(String scope, String key);

    /** Throws (exclusion violation) if the patient already has an overlapping live appointment. */
    void insert(AppointmentRow a);

    void linkSlots(UUID appointmentId, Collection<Long> slotIds);

    Optional<AppointmentRow> find(UUID id);

    Optional<AppointmentRow> lock(UUID id);

    void updateStatus(UUID id, AppointmentStatus status, String reason);

    boolean patientBusy(UUID patientId, Interval period);

    List<AppointmentRow> forPatient(UUID patientId);

    List<AppointmentRow> forPractitioner(UUID practitionerId, Interval window);

    /** BOOKED appointments that ended before {@code cutoff}, locked, skipping rows others hold. */
    List<AppointmentRow> lockOverdue(Instant cutoff, int limit);

    NoShowEstimate attendance(String clinicId, long minObservations);

    void countOutcome(String clinicId, boolean attended);

    void scheduleReminder(UUID appointmentId, String kind, Instant dueAt);

    void cancelReminders(UUID appointmentId);

    /** Live appointments per practitioner per local day, for load balancing. */
    int liveCount(UUID practitionerId, LocalDate day, java.time.ZoneId zone);
}
