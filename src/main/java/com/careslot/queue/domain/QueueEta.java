package com.careslot.queue.domain;

import java.time.Duration;

/**
 * Little's law for one doctor's queue: with throughput 1/S patients per second (S = mean consult time), a
 * patient with {@code ahead} people in front waits about {@code ahead * S}, plus whatever is left of the
 * consult in progress. It is an estimate for the waiting-room screen, not a promise; the first consult of
 * the day uses the default duration.
 */
public final class QueueEta {

    private QueueEta() {
    }

    public static Duration estimate(int ahead, ConsultDuration avg, Duration currentConsultElapsed) {
        if (ahead < 0) {
            throw new IllegalArgumentException("ahead must be >= 0");
        }
        long mean = Math.round(avg.seconds());
        long remaining = currentConsultElapsed == null ? 0 : Math.max(0, mean - currentConsultElapsed.toSeconds());
        return Duration.ofSeconds(ahead * mean + remaining);
    }
}
