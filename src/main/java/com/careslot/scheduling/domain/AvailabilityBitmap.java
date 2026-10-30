package com.careslot.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One local day of bookable start times as a bitmap of 5-minute cells, in Redis SETBIT order (bit 0 is the
 * most significant bit of byte 0). Bit 0 marks "computed" so an all-full day is told apart from a cache miss;
 * cell i is bit i + 1. 300 cells cover a 25-hour DST day.
 */
public final class AvailabilityBitmap {

    public static final int CELL_MINUTES = 5;
    public static final int MAX_CELLS = 300;
    public static final int BYTES = (MAX_CELLS + 1 + 7) / 8;

    private AvailabilityBitmap() {
    }

    /** Empty if a start is off the 5-minute grid or outside the day; the caller then just doesn't cache. */
    public static Optional<byte[]> encode(Instant dayStart, List<Instant> starts) {
        byte[] bits = new byte[BYTES];
        set(bits, 0);
        for (Instant s : starts) {
            long seconds = Duration.between(dayStart, s).getSeconds();
            if (seconds < 0 || seconds % (CELL_MINUTES * 60) != 0) {
                return Optional.empty();
            }
            long cell = seconds / (CELL_MINUTES * 60);
            if (cell >= MAX_CELLS) {
                return Optional.empty();
            }
            set(bits, (int) cell + 1);
        }
        return Optional.of(bits);
    }

    /** Empty when the bytes are missing or the computed bit is not set. */
    public static Optional<List<Instant>> decode(Instant dayStart, byte[] bits) {
        if (bits == null || bits.length == 0 || !get(bits, 0)) {
            return Optional.empty();
        }
        List<Instant> out = new ArrayList<>();
        int max = Math.min(MAX_CELLS, bits.length * 8 - 1);
        for (int cell = 0; cell < max; cell++) {
            if (get(bits, cell + 1)) {
                out.add(dayStart.plusSeconds((long) cell * CELL_MINUTES * 60));
            }
        }
        return Optional.of(out);
    }

    private static void set(byte[] b, int bit) {
        b[bit >> 3] |= (byte) (0x80 >>> (bit & 7));
    }

    private static boolean get(byte[] b, int bit) {
        return (b[bit >> 3] & (0x80 >>> (bit & 7))) != 0;
    }
}
