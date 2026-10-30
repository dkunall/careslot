package com.careslot.scheduling.application;

import com.careslot.directory.application.DirectoryService;
import com.careslot.directory.application.Resource;
import com.careslot.scheduling.domain.Interval;
import com.careslot.scheduling.domain.OverbookingPolicy;
import com.careslot.scheduling.domain.ResourceKind;
import com.careslot.shared.config.CareSlotProperties;
import com.careslot.shared.error.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bookable consult start times for one doctor on one day. Served from the Redis bitmap when present; the
 * booking path never trusts this view, so a stale cache costs at most a 409 on a slot that just went.
 */
@Service
public class AvailabilityService {

    public record DayAvailability(UUID practitionerId, LocalDate day, String zone, List<Instant> starts, boolean cached) {
    }

    private final SlotStore slots;
    private final AppointmentStore appointments;
    private final DirectoryService directory;
    private final AvailabilityCache cache;
    private final Clock clock;
    private final MeterRegistry meters;
    private final OverbookingPolicy overbooking;
    private final long minObservations;

    AvailabilityService(SlotStore slots, AppointmentStore appointments, DirectoryService directory,
                        AvailabilityCache cache, Clock clock, MeterRegistry meters, CareSlotProperties props) {
        this.slots = slots;
        this.appointments = appointments;
        this.directory = directory;
        this.cache = cache;
        this.clock = clock;
        this.meters = meters;
        this.overbooking = new OverbookingPolicy.BinomialNoShow(props.booking().maxOverflowProbability(),
                props.booking().maxExtraFraction());
        this.minObservations = props.booking().minObservations();
    }

    public DayAvailability day(UUID practitionerId, LocalDate day) {
        Resource r = directory.resource(practitionerId);
        if (r.kind() != ResourceKind.PRACTITIONER) {
            throw ApiException.invalid("not a practitioner");
        }
        ZoneId zone = directory.facility(r.facilityId()).zone();
        Optional<List<Instant>> hit = cache.get(practitionerId, day, zone);
        Instant now = clock.instant();
        if (hit.isPresent()) {
            meters.counter("careslot.availability.cache", "result", "hit").increment();
            return new DayAvailability(practitionerId, day, zone.getId(),
                    hit.get().stream().filter(now::isBefore).toList(), true);
        }
        meters.counter("careslot.availability.cache", "result", "miss").increment();
        var estimate = appointments.attendance(r.clinicId(), minObservations);
        Interval window = new Interval(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
        List<Instant> starts = slots.slots(List.of(practitionerId), window).stream()
                .filter(s -> !s.blocked() && s.booked() < overbooking.bookingLimit(s.capacity(), estimate))
                .map(s -> s.period().start()).sorted().toList();
        cache.put(practitionerId, day, zone, starts);
        return new DayAvailability(practitionerId, day, zone.getId(), starts.stream().filter(now::isBefore).toList(), false);
    }

    public void evict(UUID practitionerId, Instant start) {
        directory.practitionerZone(practitionerId).ifPresent(zone ->
                cache.evict(practitionerId, start.atZone(zone).toLocalDate()));
    }
}
