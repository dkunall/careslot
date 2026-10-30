package com.careslot.directory.application;

/**
 * Health IDs are 14 digits, shown as {@code 12-3456-7890-1234} (the shape of India's ABHA number). Only the
 * shape is checked; there is no checksum to verify and every ID in this repo is synthetic.
 */
public final class HealthIds {

    private HealthIds() {
    }

    public static String normalise(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("healthId is required");
        }
        String digits = raw.replace("-", "").replace(" ", "");
        if (!digits.matches("\\d{14}")) {
            throw new IllegalArgumentException("healthId must be 14 digits");
        }
        return digits.substring(0, 2) + "-" + digits.substring(2, 6) + "-" + digits.substring(6, 10) + "-"
                + digits.substring(10);
    }
}
