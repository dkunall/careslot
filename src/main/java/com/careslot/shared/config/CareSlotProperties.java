package com.careslot.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("careslot")
public record CareSlotProperties(Phi phi, Booking booking, Queue queue, Reminders reminders, Records records,
                                 Outbox outbox, Availability availability) {

    /** {@code key} is 32 random bytes, base64. There is no default: the app refuses to start without one. */
    public record Phi(String key) {
    }

    public record Booking(int maxAttempts, int perPatientPerMinute, double maxOverflowProbability,
                          double maxExtraFraction, long minObservations, Duration noShowGrace,
                          Duration noShowSweep, int horizonDays) {
    }

    public record Queue(Duration defaultConsult, double alpha) {
    }

    public record Reminders(Duration poll, int perSecond, Duration before1, Duration before2, int maxAttempts) {
    }

    /** {@code mode}: {@code demo} reads the local stand-in table, {@code fhir} queries a FHIR R4 server. */
    public record Records(String mode, String fhirBase, Duration timeout) {
    }

    public record Outbox(Duration poll, int batch) {
    }

    public record Availability(Duration ttl) {
    }
}
