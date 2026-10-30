package com.careslot.scheduling.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Booking lifecycle as a state machine. Each state lists where it may go next; anything else is a
 * conflict, not a silent no-op. REQUESTED exists for bookings that wait on something outside the
 * transaction (none today: a booking either reserves all its slots or fails), so it only leads to BOOKED.
 */
public enum AppointmentStatus {
    REQUESTED {
        @Override
        Set<AppointmentStatus> next() {
            return EnumSet.of(BOOKED, CANCELLED);
        }
    },
    BOOKED {
        @Override
        Set<AppointmentStatus> next() {
            return EnumSet.of(CHECKED_IN, CANCELLED, NO_SHOW);
        }
    },
    CHECKED_IN {
        @Override
        Set<AppointmentStatus> next() {
            return EnumSet.of(IN_CONSULT);
        }
    },
    IN_CONSULT {
        @Override
        Set<AppointmentStatus> next() {
            return EnumSet.of(COMPLETED);
        }
    },
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    Set<AppointmentStatus> next() {
        return EnumSet.noneOf(AppointmentStatus.class);
    }

    public boolean canMoveTo(AppointmentStatus target) {
        return next().contains(target);
    }

    /** Slots stay held while the patient may still turn up or is being seen. */
    public boolean holdsSlots() {
        return this == BOOKED || this == CHECKED_IN || this == IN_CONSULT || this == REQUESTED;
    }

    public boolean terminal() {
        return next().isEmpty();
    }

    public AppointmentStatus moveTo(AppointmentStatus target) {
        if (!canMoveTo(target)) {
            throw new IllegalStateException("cannot move appointment from " + this + " to " + target);
        }
        return target;
    }
}
