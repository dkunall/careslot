package com.careslot.scheduling.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UtilisationSimulatorTest {

    private final UtilisationSimulator.Params p = new UtilisationSimulator.Params(200, 8, 6, 0.2, 0.1, 0.6, 7L);
    private final NoShowEstimate est = new NoShowEstimate(800, 200, 100);

    @Test
    void sameSeedSameResult() {
        var a = UtilisationSimulator.run(p, new OverbookingPolicy.Fixed(0), false, est);
        var b = UtilisationSimulator.run(p, new OverbookingPolicy.Fixed(0), false, est);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void withoutOverbookingNothingOverflowsAndWaitlistHelps() {
        var plain = UtilisationSimulator.run(p, new OverbookingPolicy.Fixed(0), false, est);
        var wl = UtilisationSimulator.run(p, new OverbookingPolicy.Fixed(0), true, est);
        assertThat(plain.overflowPatients()).isZero();
        assertThat(wl.utilisation()).isGreaterThan(plain.utilisation());
    }

    @Test
    void overbookingRaisesUtilisationAtTheCostOfSomeOverflow() {
        var plain = UtilisationSimulator.run(p, new OverbookingPolicy.Fixed(0), false, est);
        var ob = UtilisationSimulator.run(p, new OverbookingPolicy.BinomialNoShow(0.25, 0.5), false, est);
        assertThat(ob.utilisation()).isGreaterThan(plain.utilisation());
        assertThat(ob.overflowSlotShare()).isGreaterThan(0).isLessThan(0.30);
    }
}
