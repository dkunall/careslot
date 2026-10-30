package com.careslot.queue.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class QueueEtaTest {

    @Test
    void firstObservationReplacesTheDefaultThenItSmooths() {
        var d = ConsultDuration.initial(Duration.ofMinutes(10));
        d = d.observe(Duration.ofMinutes(6), 0.3);
        assertThat(d.mean()).isEqualTo(Duration.ofMinutes(6));
        d = d.observe(Duration.ofMinutes(16), 0.3);
        assertThat(d.mean()).isEqualTo(Duration.ofMinutes(9)); // 0.3*16 + 0.7*6
    }

    @Test
    void outliersAreClamped() {
        var d = ConsultDuration.initial(Duration.ofMinutes(10)).observe(Duration.ofHours(5), 1.0);
        assertThat(d.mean()).isEqualTo(Duration.ofHours(1));
        d = d.observe(Duration.ofSeconds(5), 1.0);
        assertThat(d.mean()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void etaIsPeopleAheadTimesMeanPlusWhatIsLeftOfTheCurrentConsult() {
        var d = new ConsultDuration(600, 5);
        assertThat(QueueEta.estimate(3, d, Duration.ofMinutes(4))).isEqualTo(Duration.ofMinutes(36));
        assertThat(QueueEta.estimate(0, d, Duration.ofMinutes(15))).isEqualTo(Duration.ZERO);
        assertThat(QueueEta.estimate(2, d, null)).isEqualTo(Duration.ofMinutes(20));
    }
}
