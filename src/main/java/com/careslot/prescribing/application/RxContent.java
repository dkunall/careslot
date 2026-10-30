package com.careslot.prescribing.application;

import com.careslot.prescribing.domain.PrescriptionItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Canonical text of a signed prescription and its SHA-256. The hash is printed on the PDF so a pharmacist can
 * check the paper against the server copy. It is a fingerprint, not a digital signature: see README.
 */
public final class RxContent {

    private RxContent() {
    }

    public static String canonical(UUID id, UUID patientId, String prescriber, Instant signedAt,
                                   List<PrescriptionItem> items, String overrideReason) {
        StringBuilder b = new StringBuilder("careslot-rx-v1\n")
                .append(id).append('\n').append(patientId).append('\n').append(prescriber).append('\n')
                .append(signedAt).append('\n');
        int line = 1;
        for (PrescriptionItem i : items) {
            b.append(line++).append('|').append(i.drugCode()).append('|').append(i.dose()).append('|')
                    .append(i.frequency()).append('|').append(i.days()).append('\n');
        }
        b.append("override|").append(overrideReason == null ? "" : overrideReason.strip()).append('\n');
        return b.toString();
    }

    public static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
