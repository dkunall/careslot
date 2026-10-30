package com.careslot.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlotPlannerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    private static ScheduleTemplate template(String rule, ZoneId zone) {
        return new ScheduleTemplate(UUID.randomUUID(), UUID.randomUUID(), RecurrenceRule.parse(rule),
                LocalTime.of(9, 0), LocalTime.of(10, 0), 15, 3, MON, MON.plusDays(30), zone);
    }

    @Test
    void cutsEachDayIntoSlotsAndSkipsHolidays() {
        var t = template("FREQ=WEEKLY;BYDAY=MO,TU", IST);
        var slots = SlotPlanner.plan(t, MON, MON.plusDays(6), Set.of(MON.plusDays(1)), IntervalTree.of(List.of(), x -> x));
        assertThat(slots).hasSize(4).allMatch(s -> s.capacity() == 3);
        assertThat(slots.get(0).period().start()).isEqualTo(Instant.parse("2026-11-02T03:30:00Z"));
        assertThat(slots.get(3).period().end()).isEqualTo(Instant.parse("2026-11-02T04:30:00Z"));
    }

    @Test
    void dropsSlotsThatTouchALeaveButKeepsTheRest() {
        var t = template("FREQ=DAILY", IST);
        var leave = new Interval(Instant.parse("2026-11-02T03:50:00Z"), Instant.parse("2026-11-02T04:05:00Z"));
        var slots = SlotPlanner.plan(t, MON, MON, Set.of(), IntervalTree.of(List.of(leave), x -> x));
        // 09:00-09:15 stays, 09:15-09:30 and 09:30-09:45 overlap the leave, 09:45-10:00 stays
        assertThat(slots).extracting(s -> s.period().start().toString())
                .containsExactly("2026-11-02T03:30:00Z", "2026-11-02T04:15:00Z");
    }

    @Test
    void respectsTemplateValidityAndLeavesPartialBlocksOut() {
        var t = new ScheduleTemplate(UUID.randomUUID(), UUID.randomUUID(), RecurrenceRule.parse("FREQ=DAILY"),
                LocalTime.of(9, 0), LocalTime.of(9, 50), 20, 1, MON.plusDays(2), MON.plusDays(3), IST);
        var slots = SlotPlanner.plan(t, MON, MON.plusDays(10), Set.of(), IntervalTree.of(List.of(), x -> x));
        assertThat(slots).hasSize(4); // two days x (09:00, 09:20); 09:40-10:00 does not fit before 09:50
    }

    @Test
    void keepsWallClockTimesAcrossDaylightSavingChanges() {
        LocalDate sunday = LocalDate.of(2026, 11, 1); // US DST ends that night
        var t = new ScheduleTemplate(UUID.randomUUID(), UUID.randomUUID(), RecurrenceRule.parse("FREQ=DAILY"),
                LocalTime.of(9, 0), LocalTime.of(9, 15), 15, 1, sunday.minusDays(1), sunday.plusDays(1),
                ZoneId.of("America/New_York"));
        var slots = SlotPlanner.plan(t, sunday.minusDays(1), sunday, Set.of(), IntervalTree.of(List.of(), x -> x));
        assertThat(slots).extracting(s -> s.period().start().toString())
                .containsExactly("2026-10-31T13:00:00Z", "2026-11-01T14:00:00Z");
    }
}
