package com.careslot.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotAllocatorTest {

    private static final UUID DR_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID DR_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant T = Instant.parse("2026-11-02T03:30:00Z");

    private static BookingOption option(UUID dr, int minutesFromT, int load, int booked) {
        Interval p = new Interval(T.plusSeconds(minutesFromT * 60L), T.plusSeconds((minutesFromT + 15) * 60L));
        return new BookingOption(dr, p, List.of(new SlotRef(minutesFromT + (dr.equals(DR_A) ? 0 : 1000), dr,
                ResourceKind.PRACTITIONER, p, booked, 2)), load);
    }

    @Test
    void earliestFitSkipsFullOptions() {
        var full = option(DR_A, 0, 0, 2);
        var a = option(DR_A, 15, 0, 0);
        var b = option(DR_B, 30, 0, 0);
        assertThat(new SlotAllocator.EarliestFit().rank(List.of(b, full, a))).containsExactly(a, b);
    }

    @Test
    void preferredDoctorFirstEvenIfLater() {
        var a = option(DR_A, 0, 0, 0);
        var b = option(DR_B, 60, 0, 0);
        assertThat(SlotAllocator.named("PREFERRED", DR_B).rank(List.of(a, b))).containsExactly(b, a);
    }

    @Test
    void loadBalancedPicksTheLeastBusyDoctor() {
        var a = option(DR_A, 0, 9, 0);
        var b = option(DR_B, 45, 2, 0);
        assertThat(SlotAllocator.named("load_balanced", null).rank(List.of(a, b))).containsExactly(b, a);
    }

    @Test
    void lockOrderIsBySlotIdWhateverTheInputOrder() {
        Interval p = new Interval(T, T.plusSeconds(900));
        var o = new BookingOption(DR_A, p, List.of(new SlotRef(9, DR_A, ResourceKind.PRACTITIONER, p, 0, 1),
                new SlotRef(3, UUID.randomUUID(), ResourceKind.ROOM, p, 0, 1),
                new SlotRef(5, UUID.randomUUID(), ResourceKind.EQUIPMENT, p, 0, 1)), 0);
        assertThat(o.lockOrder()).extracting(SlotRef::id).containsExactly(3L, 5L, 9L);
    }

    @Test
    void unknownStrategyIsRejected() {
        assertThatThrownBy(() -> SlotAllocator.named("RANDOM", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlotAllocator.named("PREFERRED", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
