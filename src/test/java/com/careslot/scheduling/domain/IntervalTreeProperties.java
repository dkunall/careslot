package com.careslot.scheduling.domain;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class IntervalTreeProperties {

    record Item(int id, Interval iv) {
    }

    private static Interval iv(int start, int len) {
        Instant base = Instant.parse("2026-11-02T00:00:00Z");
        return new Interval(base.plusSeconds(start * 60L), base.plusSeconds((start + len) * 60L));
    }

    @Property(tries = 300)
    void overlapQueryMatchesBruteForce(@ForAll @Size(max = 60) List<@IntRange(min = 0, max = 2000) Integer> starts,
                                       @ForAll @IntRange(min = 1, max = 300) int len,
                                       @ForAll @IntRange(min = 0, max = 2200) int qStart,
                                       @ForAll @IntRange(min = 1, max = 400) int qLen) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            items.add(new Item(i, iv(starts.get(i), len + (i % 7) * 13)));
        }
        var tree = IntervalTree.of(items, Item::iv);
        Interval q = iv(qStart, qLen);
        Set<Integer> expected = items.stream().filter(x -> x.iv().overlaps(q)).map(Item::id).collect(Collectors.toSet());
        Set<Integer> actual = tree.overlapping(q).stream().map(Item::id).collect(Collectors.toSet());
        assertThat(actual).isEqualTo(expected);
        assertThat(tree.anyOverlap(q)).isEqualTo(!expected.isEmpty());
    }

    @Property(tries = 50)
    void touchingIntervalsDoNotOverlap(@ForAll @IntRange(min = 0, max = 1000) int start,
                                       @ForAll @IntRange(min = 1, max = 100) int len) {
        var tree = IntervalTree.of(List.of(iv(start, len)), x -> x);
        assertThat(tree.anyOverlap(iv(start + len, 10))).isFalse();
        assertThat(tree.anyOverlap(iv(Math.max(0, start - 10), Math.min(10, start) == 0 ? 1 : Math.min(10, start))))
                .isEqualTo(start == 0);
    }
}
