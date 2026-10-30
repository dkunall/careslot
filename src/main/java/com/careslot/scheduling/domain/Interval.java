package com.careslot.scheduling.domain;

import java.time.Instant;

/** Half-open {@code [start, end)}, the same convention as Postgres {@code tstzrange '[)'}. */
public record Interval(Instant start, Instant end) {

    public Interval {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("interval end must be after start");
        }
    }

    public boolean overlaps(Interval o) {
        return start.isBefore(o.end) && o.start.isBefore(end);
    }

    public boolean contains(Interval o) {
        return !o.start.isBefore(start) && !o.end.isAfter(end);
    }
}
