package com.careslot.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WaitlistRankingTest {

    private static final UUID DR = UUID.randomUUID();
    private static final Instant T = Instant.parse("2026-11-02T03:30:00Z");
    private static final Interval DAY = new Interval(T, T.plusSeconds(8 * 3600));

    private static WaitlistEntry entry(int priority, int createdMinutesAgo, Interval window, UUID dr) {
        return new WaitlistEntry(UUID.randomUUID(), UUID.randomUUID(), dr, window, priority,
                T.minusSeconds(createdMinutesAgo * 60L));
    }

    @Test
    void priorityThenLongestWaitingAndOnlyMatchingWindows() {
        var urgent = entry(5, 1, DAY, DR);
        var oldRoutine = entry(1, 300, DAY, DR);
        var newRoutine = entry(1, 10, DAY, DR);
        var otherDoctor = entry(9, 500, DAY, UUID.randomUUID());
        var afternoonOnly = entry(9, 500, new Interval(T.plusSeconds(5 * 3600), T.plusSeconds(8 * 3600)), DR);
        Interval freed = new Interval(T.plusSeconds(3600), T.plusSeconds(3600 + 900));
        var pq = WaitlistRanking.candidates(List.of(newRoutine, otherDoctor, oldRoutine, afternoonOnly, urgent), DR, freed);
        assertThat(WaitlistRanking.drain(pq)).containsExactly(urgent, oldRoutine, newRoutine);
    }
}
