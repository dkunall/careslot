package com.careslot.notify.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReminderStore {

    record Due(long id, String kind, UUID appointmentId, UUID patientId, UUID practitionerId, Instant start,
               int attempts) {
    }

    /** Lock up to {@code limit} due reminders of BOOKED appointments, skipping rows other workers hold. */
    List<Due> claimDue(Instant now, int limit);

    /** Reminders whose appointment no longer holds (cancelled, rescheduled away, seen) are dropped. */
    int cancelStale();

    void markSent(long id, Instant at);

    void markFailed(long id, boolean giveUp);
}
