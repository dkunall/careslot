package com.careslot.scheduling.domain;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OverbookingPolicyTest {

    private final OverbookingPolicy binomial = new OverbookingPolicy.BinomialNoShow(0.25, 0.5);

    @Test
    void neverOverbooksOnTooLittleHistory() {
        assertThat(binomial.bookingLimit(4, new NoShowEstimate(30, 20, 200))).isEqualTo(4);
        assertThat(binomial.bookingLimit(4, new NoShowEstimate(1000, 0, 200))).isEqualTo(4);
    }

    @Test
    void overbooksMoreWhenMorePeopleStayAway() {
        int low = binomial.bookingLimit(8, new NoShowEstimate(950, 50, 200));
        int high = binomial.bookingLimit(8, new NoShowEstimate(700, 300, 200));
        assertThat(low).isGreaterThanOrEqualTo(8);
        assertThat(high).isGreaterThan(low).isLessThanOrEqualTo(12);
    }

    @Test
    void chosenLimitKeepsOverflowRiskUnderTheThreshold() {
        // capacity 4, 30% no-show: booking 5 overflows only if all 5 come (0.7^5 = 0.168); booking 6 overflows
        // with P(X >= 5 of 6) = 0.420, above the 0.25 threshold, so the limit is 5
        assertThat(binomial.bookingLimit(4, new NoShowEstimate(700, 300, 200))).isEqualTo(5);
        // at 20% no-show even one extra is too risky for capacity 4 (0.8^5 = 0.328)
        assertThat(binomial.bookingLimit(4, new NoShowEstimate(800, 200, 200))).isEqualTo(4);
    }

    @Test
    void fixedPolicyIgnoresHistory() {
        assertThat(new OverbookingPolicy.Fixed(0).bookingLimit(3, new NoShowEstimate(0, 900, 1))).isEqualTo(3);
        assertThat(new OverbookingPolicy.Fixed(1).bookingLimit(3, new NoShowEstimate(0, 0, 1))).isEqualTo(4);
    }

    @Property(tries = 200)
    void upperTailMatchesDirectSum(@ForAll @IntRange(min = 1, max = 30) int n, @ForAll @DoubleRange(min = 0.01, max = 0.99) double p,
                                   @ForAll @IntRange(min = 0, max = 31) int k) {
        double direct = 0;
        for (int i = Math.max(k, 0); i <= n; i++) {
            direct += choose(n, i) * Math.pow(p, i) * Math.pow(1 - p, n - i);
        }
        assertThat(Binomial.upperTail(n, p, k)).isCloseTo(Math.min(1.0, direct), within(1e-9));
    }

    @Property(tries = 200)
    void limitNeverExceedsCapAndRespectsRisk(@ForAll @IntRange(min = 1, max = 30) int capacity,
                                             @ForAll @DoubleRange(min = 0.0, max = 0.6) double rate) {
        var est = new NoShowEstimate(Math.round(1000 * (1 - rate)), Math.round(1000 * rate), 100);
        int n = binomial.bookingLimit(capacity, est);
        assertThat(n).isBetween(capacity, capacity + Math.max(1, capacity / 2));
        if (n > capacity) {
            assertThat(Binomial.upperTail(n, 1 - est.rate(), capacity + 1)).isLessThanOrEqualTo(0.25);
        }
    }

    private static double choose(int n, int k) {
        double r = 1;
        for (int i = 1; i <= k; i++) {
            r = r * (n - k + i) / i;
        }
        return r;
    }
}
