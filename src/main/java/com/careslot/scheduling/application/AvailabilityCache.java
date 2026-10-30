package com.careslot.scheduling.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cache of bookable start times per practitioner per local day. Missing or stale is safe: booking re-checks. */
public interface AvailabilityCache {

    Optional<List<Instant>> get(UUID practitionerId, LocalDate day, java.time.ZoneId zone);

    void put(UUID practitionerId, LocalDate day, java.time.ZoneId zone, List<Instant> starts);

    void evict(UUID practitionerId, LocalDate day);
}
