package com.careslot.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceRuleTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    @Test
    void weeklyByDayFiresOnListedDaysOnly() {
        var rule = RecurrenceRule.parse("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR");
        var days = rule.expand(MON, MON, MON.plusDays(13));
        assertThat(days).hasSize(6).allMatch(d -> d.getDayOfWeek() == DayOfWeek.MONDAY
                || d.getDayOfWeek() == DayOfWeek.WEDNESDAY || d.getDayOfWeek() == DayOfWeek.FRIDAY);
    }

    @Test
    void fortnightlyCountsWeeksFromTheAnchorWeek() {
        // anchored on a Wednesday: the Tuesday of the anchor week is "week 0" even though it is before the anchor
        var rule = RecurrenceRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH");
        var days = rule.expand(MON.plusDays(2), MON, MON.plusDays(27));
        assertThat(days).containsExactly(MON.plusDays(3), MON.plusDays(15), MON.plusDays(17));
    }

    @Test
    void weeklyWithoutByDayUsesTheAnchorDay() {
        var days = RecurrenceRule.parse("FREQ=WEEKLY").expand(MON.plusDays(1), MON, MON.plusDays(20));
        assertThat(days).containsExactly(MON.plusDays(1), MON.plusDays(8), MON.plusDays(15));
    }

    @Test
    void dailyInterval() {
        var days = RecurrenceRule.parse("FREQ=DAILY;INTERVAL=3").expand(MON, MON.plusDays(1), MON.plusDays(9));
        assertThat(days).containsExactly(MON.plusDays(3), MON.plusDays(6), MON.plusDays(9));
    }

    @Test
    void rejectsWhatItDoesNotUnderstand() {
        assertThatThrownBy(() -> RecurrenceRule.parse("FREQ=MONTHLY")).hasMessageContaining("FREQ");
        assertThatThrownBy(() -> RecurrenceRule.parse("FREQ=WEEKLY;COUNT=4")).hasMessageContaining("COUNT");
        assertThatThrownBy(() -> RecurrenceRule.parse("FREQ=DAILY;BYDAY=MO")).hasMessageContaining("BYDAY");
        assertThatThrownBy(() -> RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=XX")).hasMessageContaining("XX");
        assertThatThrownBy(() -> RecurrenceRule.parse("INTERVAL=2")).hasMessageContaining("FREQ");
        assertThatThrownBy(() -> RecurrenceRule.parse("FREQ=DAILY;INTERVAL=0")).hasMessageContaining("INTERVAL");
    }
}
