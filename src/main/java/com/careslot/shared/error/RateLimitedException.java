package com.careslot.shared.error;

public final class RateLimitedException extends ApiException {

    private final int retryAfterSeconds;

    public RateLimitedException(String message, int retryAfterSeconds) {
        super(429, "rate-limited", message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
