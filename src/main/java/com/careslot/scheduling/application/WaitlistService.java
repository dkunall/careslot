package com.careslot.scheduling.application;

import com.careslot.directory.application.DirectoryService;
import com.careslot.directory.application.Resource;
import com.careslot.scheduling.domain.Interval;
import com.careslot.shared.domain.ResourceKind;
import com.careslot.scheduling.domain.WaitlistEntry;
import com.careslot.scheduling.domain.WaitlistRanking;
import com.careslot.shared.error.ApiException;
import com.careslot.shared.events.AppointmentEvent;
import com.careslot.shared.events.ProcessedEvents;
import com.careslot.shared.security.Actor;
import com.careslot.shared.security.Role;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Patients waiting for an earlier consult with a given doctor. When a place frees up, the best-ranked entry
 * whose window covers it is booked into it: priority first (front desk sets it, 0..9), then who waited
 * longest. The fill runs off the CANCELLED event, at least once; the processed-event row and the
 * per-(entry, cancellation) idempotency key make a redelivery a no-op.
 */
@Service
public class WaitlistService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);
    static final String CONSUMER = "waitlist-matcher";

    private final WaitlistStore store;
    private final BookingService booking;
    private final DirectoryService directory;
    private final ProcessedEvents processed;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    WaitlistService(WaitlistStore store, BookingService booking, DirectoryService directory, ProcessedEvents processed,
                    TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.booking = booking;
        this.directory = directory;
        this.processed = processed;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    public UUID join(Actor actor, UUID patientId, UUID practitionerId, Interval window, int priority) {
        boolean desk = actor.hasAny(Role.FRONT_DESK, Role.ADMIN);
        if (!desk) {
            directory.authorisePatientAccess(actor, patientId);
            if (actor.staff()) {
                throw ApiException.forbidden("forbidden", "front desk manages the waitlist");
            }
            priority = 0; // patients may join, not jump the queue
        }
        if (priority < 0 || priority > 9) {
            throw ApiException.invalid("priority is 0..9");
        }
        Resource r = directory.resource(practitionerId);
        if (r.kind() != ResourceKind.PRACTITIONER) {
            throw ApiException.invalid("not a practitioner");
        }
        if (!window.end().isAfter(clock.instant())) {
            throw ApiException.invalid("window is in the past");
        }
        WaitlistEntry e = new WaitlistEntry(UUID.randomUUID(), patientId, practitionerId, window, priority,
                clock.instant());
        if (!store.insert(e, actor.subject())) {
            throw ApiException.conflict("already-waiting", "the patient is already on this doctor's waitlist");
        }
        return e.id();
    }

    public void withdraw(Actor actor, UUID entryId) {
        WaitlistStore.Row row = store.find(entryId).orElseThrow(() -> ApiException.notFound("waitlist entry"));
        if (!actor.hasAny(Role.FRONT_DESK, Role.ADMIN)) {
            directory.authorisePatientAccess(actor, row.entry().patientId());
        }
        if (!store.withdraw(entryId) && !"WITHDRAWN".equals(row.status())) {
            throw ApiException.conflict("invalid-state", "entry is " + row.status());
        }
    }

    public List<WaitlistStore.Row> forPractitioner(Actor actor, UUID practitionerId) {
        if (!actor.staff()) {
            throw ApiException.forbidden("forbidden", "staff only");
        }
        return store.forPractitioner(practitionerId);
    }

    /** Returns the appointment the freed place went to, if any. */
    public Optional<UUID> onFreed(AppointmentEvent event) {
        if (!event.freesCapacity()) {
            return Optional.empty();
        }
        Interval freed = new Interval(event.start(), event.end());
        if (!freed.start().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return tx.execute(status -> {
            if (!processed.firstTime(CONSUMER, event.eventId())) {
                meters.counter("careslot.waitlist.duplicate_events").increment();
                return Optional.<UUID>empty();
            }
            List<WaitlistEntry> ranked = WaitlistRanking.drain(WaitlistRanking.candidates(
                    store.lockCandidates(event.practitionerId(), freed), event.practitionerId(), freed));
            for (WaitlistEntry e : ranked) {
                BookingCommand cmd = new BookingCommand(e.patientId(), event.clinicId(), AppointmentKind.CONSULT,
                        event.practitionerId(), freed, "PREFERRED", List.of());
                BookingService.Outcome o = booking.attempt(status, cmd, "waitlist",
                        e.id() + ":" + event.appointmentId(), "system:waitlist", null);
                UUID appt = switch (o) {
                    case BookingService.Outcome.Booked b -> b.appointment().id();
                    case BookingService.Outcome.Replayed r -> r.appointment().id();
                    default -> null;
                };
                if (appt != null) {
                    store.markBooked(e.id(), appt);
                    meters.counter("careslot.waitlist.filled").increment();
                    log.info("waitlist entry {} booked into {}", e.id(), appt);
                    return Optional.of(appt);
                }
            }
            return Optional.<UUID>empty();
        });
    }
}
