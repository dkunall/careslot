package com.careslot.scheduling.domain;

import java.time.Instant;
import java.util.UUID;

/** A patient who wants an earlier slot with a doctor, any time inside {@code window}. Higher priority first. */
public record WaitlistEntry(UUID id, UUID patientId, UUID practitionerId, Interval window, int priority,
                            Instant createdAt) {

    public boolean accepts(UUID practitioner, Interval freed) {
        return practitionerId.equals(practitioner) && window.contains(freed);
    }
}
