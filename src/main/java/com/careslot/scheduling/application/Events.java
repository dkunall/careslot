package com.careslot.scheduling.application;

import com.careslot.shared.events.AppointmentEvent;

import java.time.Instant;
import java.util.UUID;

final class Events {

    private Events() {
    }

    static AppointmentEvent of(AppointmentEvent.Type type, AppointmentRow a) {
        return new AppointmentEvent(UUID.randomUUID(), type, a.id(), a.patientId(), a.practitionerId(), a.clinicId(),
                a.period().start(), a.period().end(), Instant.now());
    }
}
