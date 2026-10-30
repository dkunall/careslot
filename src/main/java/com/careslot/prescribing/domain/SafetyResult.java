package com.careslot.prescribing.domain;

import java.util.Comparator;
import java.util.List;

/**
 * Outcome of a check. {@code checked = false} means the record system could not be read, so nothing is known
 * about the patient's current medicines or allergies; signing then fails closed like a SEVERE finding.
 */
public record SafetyResult(boolean checked, List<Finding> findings, String source) {

    public SafetyResult {
        findings = findings.stream().sorted(Comparator.comparing(Finding::severity).reversed()
                .thenComparing(Finding::rule)).toList();
    }

    public static SafetyResult unchecked(String reason) {
        return new SafetyResult(false, List.of(), reason);
    }

    public boolean hasSevere() {
        return findings.stream().anyMatch(f -> f.severity() == Severity.SEVERE);
    }

    public boolean blocksSigning() {
        return !checked || hasSevere();
    }
}
