package com.careslot.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published for every booking change. Carries ids and times only: no names, no diagnosis, no medicines,
 * so the topic can be read by the reminder worker and the cache without seeing PHI.
 */
public record AppointmentEvent(UUID eventId, Type type, UUID appointmentId, UUID patientId, UUID practitionerId,
                               String clinicId, Instant start, Instant end, Instant at) {

    public enum Type { BOOKED, CANCELLED, RESCHEDULED_AWAY, NO_SHOW, CHECKED_IN, COMPLETED }

    public static final String TOPIC = "careslot.appointments";

    /** A slot came free: what the waitlist matcher listens for. */
    public boolean freesCapacity() {
        return type == Type.CANCELLED || type == Type.RESCHEDULED_AWAY;
    }
}
