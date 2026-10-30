package com.careslot.queue.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record QueueToken(UUID id, UUID appointmentId, UUID practitionerId, LocalDate day, int number, String status,
                         Instant checkedInAt, Instant calledAt, Instant doneAt) {
}
