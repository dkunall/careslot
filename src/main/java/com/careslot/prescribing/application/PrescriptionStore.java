package com.careslot.prescribing.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PrescriptionStore {

    void insertDraft(Prescription draft);

    Optional<Prescription> find(UUID id);

    Optional<Prescription> lock(UUID id);

    void updateSafety(UUID id, String safetyJson);

    /** @throws org.springframework.dao.DuplicateKeyException when the sign key is already used elsewhere */
    void markSigned(UUID id, String signKey, String overrideReason, String contentSha256, Instant signedAt,
                    String safetyJson);
}
