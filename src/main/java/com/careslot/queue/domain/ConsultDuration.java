package com.careslot.queue.domain;

import java.time.Duration;

/**
 * Exponentially weighted moving average of one doctor's recent consult lengths. Recent consults count more,
 * so a doctor who slows down after lunch shows up in the ETA within a few patients. Until the first consult
 * of the day finishes, the configured default stands in.
 */
public record ConsultDuration(double seconds, long samples) {

    public static ConsultDuration initial(Duration defaultConsult) {
        return new ConsultDuration(defaultConsult.toSeconds(), 0);
    }

    public ConsultDuration observe(Duration consult, double alpha) {
        if (alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be in (0,1]");
        }
        // clamp outliers (a consult left open over lunch) so one bad record cannot wreck the queue estimate
        double s = Math.max(60, Math.min(consult.toSeconds(), 3600));
        double next = samples == 0 ? s : alpha * s + (1 - alpha) * seconds;
        return new ConsultDuration(next, samples + 1);
    }

    public Duration mean() {
        return Duration.ofSeconds(Math.round(seconds));
    }
}
