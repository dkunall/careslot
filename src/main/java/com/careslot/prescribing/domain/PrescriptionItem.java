package com.careslot.prescribing.domain;

import java.util.Set;

public record PrescriptionItem(String drugCode, String dose, String frequency, int days) {

    /** Indian OPD shorthand, which is what doctors type. */
    public static final Set<String> FREQUENCIES = Set.of("OD", "BD", "TDS", "QID", "HS", "SOS", "STAT", "WEEKLY");

    public PrescriptionItem {
        if (drugCode == null || drugCode.isBlank()) {
            throw new IllegalArgumentException("drug code is required");
        }
        if (dose == null || dose.isBlank() || dose.length() > 40) {
            throw new IllegalArgumentException("dose is required, at most 40 characters");
        }
        if (frequency == null || !FREQUENCIES.contains(frequency)) {
            throw new IllegalArgumentException("frequency must be one of " + FREQUENCIES);
        }
        if (days < 1 || days > 180) {
            throw new IllegalArgumentException("days must be 1..180");
        }
    }
}
