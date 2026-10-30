package com.careslot.scheduling.application;

import com.careslot.scheduling.domain.AppointmentStatus;
import com.careslot.scheduling.domain.Interval;

import java.util.UUID;

public record AppointmentRow(UUID id, UUID patientId, UUID practitionerId, String clinicId, AppointmentKind kind,
                             Interval period, AppointmentStatus status, boolean overbooked, String idempotencyScope,
                             String idempotencyKey, String requestHash, UUID rescheduledFrom, String createdBy,
                             int version) {
}
