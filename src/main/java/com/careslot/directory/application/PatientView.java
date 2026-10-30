package com.careslot.directory.application;

import java.util.UUID;

/** Decrypted patient identity. Only built on audited read paths. */
public record PatientView(UUID id, String healthId, String name, String phone, String email, Integer birthYear) {
}
