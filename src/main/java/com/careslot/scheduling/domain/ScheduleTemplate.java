package com.careslot.scheduling.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A recurring roster block for one resource (a doctor, a room or a machine): "Mon/Wed/Fri 09:00-13:00 in
 * 15-minute slots, 3 patients per slot". Local wall-clock times in the facility's zone, so a DST shift in a
 * zone that has one keeps the clinic opening at 09:00.
 */
public record ScheduleTemplate(UUID id, UUID resourceId, RecurrenceRule rule, LocalTime dayStart, LocalTime dayEnd,
                               int slotMinutes, int capacity, LocalDate validFrom, LocalDate validTo, ZoneId zone) {

    public ScheduleTemplate {
        if (!dayEnd.isAfter(dayStart)) {
            throw new IllegalArgumentException("day end must be after day start");
        }
        if (slotMinutes < 5 || slotMinutes > 240) {
            throw new IllegalArgumentException("slot length must be 5..240 minutes");
        }
        if (capacity < 1 || capacity > 50) {
            throw new IllegalArgumentException("slot capacity must be 1..50");
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("validTo is before validFrom");
        }
    }
}
