package com.careslot.scheduling.application;

import com.careslot.scheduling.domain.Interval;
import com.careslot.scheduling.domain.ResourceKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BookingCombinationsTest {

    private static final Interval P = new Interval(Instant.parse("2026-11-02T04:00:00Z"), Instant.parse("2026-11-02T04:30:00Z"));

    private static SlotRow slot(long id) {
        return new SlotRow(id, UUID.randomUUID(), ResourceKind.ROOM, P, 1, 0, false);
    }

    private static List<Long> ids(List<SlotRow> combo) {
        return combo.stream().map(SlotRow::id).toList();
    }

    @Test
    void bestPickFirstThenFallbacksCappedPerDoctorSlot() {
        var combos = BookingService.combinations(List.of(List.of(slot(1), slot(2), slot(3)), List.of(slot(10), slot(11))));
        assertThat(combos).hasSize(BookingService.COMBOS_PER_SLOT);
        assertThat(ids(combos.get(0))).containsExactly(1L, 10L);
        assertThat(combos.stream().map(BookingCombinationsTest::ids).toList().subList(1, 3))
                .containsExactlyInAnyOrder(List.of(2L, 10L), List.of(1L, 11L));
    }

    @Test
    void aSlotIsNeverUsedTwiceInOneCombination() {
        SlotRow shared = slot(5);
        var combos = BookingService.combinations(List.of(List.of(shared, slot(6)), List.of(shared, slot(7))));
        assertThat(combos).allSatisfy(c -> assertThat(ids(c)).doesNotHaveDuplicates());
        assertThat(combos).hasSize(3);
    }

    @Test
    void anEmptyGroupMeansNoOption() {
        assertThat(BookingService.combinations(List.of(List.of(slot(1)), List.of()))).isEmpty();
        assertThat(BookingService.combinations(List.of())).containsExactly(List.of());
    }
}
