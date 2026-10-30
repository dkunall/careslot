package com.careslot.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityBitmapTest {

    private static final Instant DAY = Instant.parse("2026-11-01T18:30:00Z");

    @Test
    void aFullDayIsNotACacheMiss() {
        byte[] bits = AvailabilityBitmap.encode(DAY, List.of()).orElseThrow();
        assertThat(AvailabilityBitmap.decode(DAY, bits)).contains(List.of());
        assertThat(AvailabilityBitmap.decode(DAY, new byte[AvailabilityBitmap.BYTES])).isEmpty();
        assertThat(AvailabilityBitmap.decode(DAY, null)).isEmpty();
    }

    @Test
    void offGridOrOutOfDayStartsAreNotCached() {
        assertThat(AvailabilityBitmap.encode(DAY, List.of(DAY.plusSeconds(7 * 60)))).isEmpty();
        assertThat(AvailabilityBitmap.encode(DAY, List.of(DAY.minusSeconds(300)))).isEmpty();
        assertThat(AvailabilityBitmap.encode(DAY, List.of(DAY.plusSeconds(300L * 300)))).isEmpty();
    }

    @Test
    void bitOrderMatchesRedisSetbit() {
        // SETBIT key 0 1 and SETBIT key 1 1 (cell 0) give 0b1100_0000
        byte[] bits = AvailabilityBitmap.encode(DAY, List.of(DAY)).orElseThrow();
        assertThat(bits[0]).isEqualTo((byte) 0xC0);
    }
}
