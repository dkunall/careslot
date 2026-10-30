package com.careslot.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Expands a template into concrete slots for a date range, dropping holidays and anything that overlaps a
 * leave. Leaves are looked up through an interval tree because one doctor's leave list is checked once per
 * generated slot.
 */
public final class SlotPlanner {

    public record PlannedSlot(Interval period, int capacity) {
    }

    private SlotPlanner() {
    }

    public static List<PlannedSlot> plan(ScheduleTemplate t, LocalDate from, LocalDate to, Set<LocalDate> holidays,
                                         IntervalTree<Interval> leaves) {
        LocalDate start = from.isBefore(t.validFrom()) ? t.validFrom() : from;
        LocalDate end = t.validTo() != null && t.validTo().isBefore(to) ? t.validTo() : to;
        List<PlannedSlot> out = new ArrayList<>();
        if (end.isBefore(start)) {
            return out;
        }
        for (LocalDate day : t.rule().expand(t.validFrom(), start, end)) {
            if (holidays.contains(day)) {
                continue;
            }
            for (LocalTime s = t.dayStart(); ; s = s.plusMinutes(t.slotMinutes())) {
                LocalTime e = s.plusMinutes(t.slotMinutes());
                if (e.isAfter(t.dayEnd()) || e.isBefore(s)) {
                    break; // the block does not fit, or the wall clock wrapped past midnight
                }
                Instant si = day.atTime(s).atZone(t.zone()).toInstant();
                Instant ei = day.atTime(e).atZone(t.zone()).toInstant();
                if (!ei.isAfter(si)) {
                    continue; // DST gap swallowed the slot
                }
                Interval period = new Interval(si, ei);
                if (!leaves.anyOverlap(period)) {
                    out.add(new PlannedSlot(period, t.capacity()));
                }
                if (!e.isBefore(t.dayEnd())) {
                    break;
                }
            }
        }
        return out;
    }
}
