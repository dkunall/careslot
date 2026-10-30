package com.careslot.prescribing.domain;

/**
 * Whether a draft may be signed. A blocking result (severe finding, or records unreadable) can be signed only
 * with an override that carries a real reason; the override itself is recorded with the signature.
 */
public enum SignDecision {
    ALLOWED, ALLOWED_WITH_OVERRIDE, BLOCKED_SEVERE, BLOCKED_UNCHECKED, OVERRIDE_REASON_TOO_SHORT;

    public static final int MIN_REASON = 15;

    public static SignDecision decide(SafetyResult result, String overrideReason) {
        if (!result.blocksSigning()) {
            return ALLOWED;
        }
        if (overrideReason == null || overrideReason.isBlank()) {
            return result.checked() ? BLOCKED_SEVERE : BLOCKED_UNCHECKED;
        }
        if (overrideReason.strip().length() < MIN_REASON) {
            return OVERRIDE_REASON_TOO_SHORT;
        }
        return ALLOWED_WITH_OVERRIDE;
    }

    public boolean allowed() {
        return this == ALLOWED || this == ALLOWED_WITH_OVERRIDE;
    }
}
