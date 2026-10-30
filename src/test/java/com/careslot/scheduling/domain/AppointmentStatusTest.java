package com.careslot.scheduling.domain;

import org.junit.jupiter.api.Test;

import static com.careslot.scheduling.domain.AppointmentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentStatusTest {

    @Test
    void happyPath() {
        assertThat(REQUESTED.moveTo(BOOKED).moveTo(CHECKED_IN).moveTo(IN_CONSULT).moveTo(COMPLETED)).isEqualTo(COMPLETED);
    }

    @Test
    void onlyBookedAppointmentsCanBeCancelledOrMarkedNoShow() {
        assertThat(BOOKED.canMoveTo(CANCELLED)).isTrue();
        assertThat(BOOKED.canMoveTo(NO_SHOW)).isTrue();
        assertThat(CHECKED_IN.canMoveTo(CANCELLED)).isFalse();
        assertThat(CHECKED_IN.canMoveTo(NO_SHOW)).isFalse();
        assertThatThrownBy(() -> COMPLETED.moveTo(CANCELLED)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalStatesReleaseSlots() {
        for (var s : AppointmentStatus.values()) {
            assertThat(s.holdsSlots()).as(s.name()).isEqualTo(!s.terminal());
        }
    }
}
