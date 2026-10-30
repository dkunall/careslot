package com.careslot.scheduling.domain;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One way to satisfy a request: a practitioner slot plus one slot for every other resource the visit needs
 * (a room, an ultrasound), all covering the same period. {@code practitionerLoad} is how many patients that
 * doctor already has that day, which the load-balancing strategy uses.
 */
public record BookingOption(UUID practitionerId, Interval period, List<SlotRef> slots, int practitionerLoad) {

    public BookingOption {
        slots = List.copyOf(slots);
    }

    /**
     * Slots in id order. Every booking locks rows in this order, so two bookings that need the same doctor and
     * the same room can never each hold one and wait for the other.
     */
    public List<SlotRef> lockOrder() {
        return slots.stream().sorted(Comparator.comparingLong(SlotRef::id)).toList();
    }
}
