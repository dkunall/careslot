package com.careslot.scheduling.application;

import com.careslot.scheduling.domain.Interval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Book this patient into the clinic somewhere in {@code window}". {@code needs} lists extra resources a
 * procedure requires, one group per need: one free slot from each group (say any of two ultrasound rooms,
 * and the one portable ECG) must cover the doctor's slot.
 */
public record BookingCommand(UUID patientId, String clinicId, AppointmentKind kind, UUID practitionerId,
                             Interval window, String strategy, List<List<UUID>> needs) {

    public static final Duration MAX_WINDOW = Duration.ofDays(14);

    public BookingCommand {
        needs = needs == null ? List.of() : needs.stream().map(List::copyOf).toList();
        if (Duration.between(window.start(), window.end()).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("search window is at most 14 days");
        }
        if (kind == AppointmentKind.CONSULT && !needs.isEmpty()) {
            throw new IllegalArgumentException("a consult needs no rooms or equipment; book a PROCEDURE");
        }
        if (needs.size() > 4 || needs.stream().anyMatch(g -> g.isEmpty() || g.size() > 10)) {
            throw new IllegalArgumentException("up to 4 needs, each with 1..10 candidate resources");
        }
    }

    /** What an idempotency key is bound to: replaying the key with a different request is an error. */
    public String fingerprint() {
        String canonical = String.join("|", patientId.toString(), clinicId, kind.name(),
                String.valueOf(practitionerId), window.start().toString(), window.end().toString(),
                String.valueOf(strategy).toUpperCase(java.util.Locale.ROOT),
                needs.stream().map(g -> g.stream().map(UUID::toString).collect(Collectors.joining(",")))
                        .collect(Collectors.joining(";")));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
