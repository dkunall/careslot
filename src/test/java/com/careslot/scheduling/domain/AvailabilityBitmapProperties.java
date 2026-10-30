package com.careslot.scheduling.domain;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.UniqueElements;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityBitmapProperties {

    private static final Instant DAY = Instant.parse("2026-11-01T18:30:00Z");

    @Property(tries = 300)
    void gridStartsRoundTrip(@ForAll @UniqueElements List<@IntRange(min = 0, max = 299) Integer> cells) {
        List<Instant> starts = cells.stream().sorted().map(c -> DAY.plusSeconds(c * 300L)).toList();
        byte[] bits = AvailabilityBitmap.encode(DAY, starts).orElseThrow();
        assertThat(bits).hasSize(AvailabilityBitmap.BYTES);
        assertThat(AvailabilityBitmap.decode(DAY, bits)).contains(starts);
    }
}
