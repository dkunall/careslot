package com.careslot.prescribing.application;

import com.careslot.prescribing.domain.PrescriptionItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Prescription(UUID id, UUID patientId, UUID appointmentId, String prescriber, String status,
                           String safetyJson, String overrideReason, String signKey, String contentSha256,
                           Instant signedAt, Instant createdAt, List<PrescriptionItem> items) {

    public boolean signed() {
        return "SIGNED".equals(status);
    }
}
