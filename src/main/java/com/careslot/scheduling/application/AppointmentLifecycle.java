package com.careslot.scheduling.application;

import com.careslot.scheduling.domain.AppointmentStatus;
import com.careslot.shared.config.CareSlotProperties;
import com.careslot.shared.error.ApiException;
import com.careslot.shared.events.AppointmentEvent;
import com.careslot.shared.events.EventOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** State changes after booking: check-in, consult, completion, no-show. Callers bring the transaction. */
@Service
public class AppointmentLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AppointmentLifecycle.class);

    private final AppointmentStore appointments;
    private final EventOutbox outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration grace;
    private final MeterRegistry meters;

    AppointmentLifecycle(AppointmentStore appointments, EventOutbox outbox, TransactionTemplate tx, Clock clock,
                         CareSlotProperties props, MeterRegistry meters) {
        this.appointments = appointments;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.grace = props.booking().noShowGrace();
        this.meters = meters;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AppointmentRow lock(UUID id) {
        return appointments.lock(id).orElseThrow(() -> ApiException.notFound("appointment"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AppointmentRow move(AppointmentRow a, AppointmentStatus to) {
        if (!a.status().canMoveTo(to)) {
            throw ApiException.conflict("invalid-state", "cannot move a " + a.status() + " appointment to " + to);
        }
        appointments.updateStatus(a.id(), to, null);
        switch (to) {
            case CHECKED_IN -> outbox.append(Events.of(AppointmentEvent.Type.CHECKED_IN, a));
            case COMPLETED -> {
                appointments.countOutcome(a.clinicId(), true);
                outbox.append(Events.of(AppointmentEvent.Type.COMPLETED, a));
            }
            case NO_SHOW -> {
                appointments.countOutcome(a.clinicId(), false);
                appointments.cancelReminders(a.id());
                outbox.append(Events.of(AppointmentEvent.Type.NO_SHOW, a));
            }
            default -> {
            }
        }
        return appointments.find(a.id()).orElseThrow();
    }

    /**
     * BOOKED appointments whose end passed more than the grace period ago become NO_SHOW, which also feeds the
     * clinic's no-show estimate. Rows are claimed with SKIP LOCKED, so concurrent sweepers split the work.
     */
    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "${careslot.booking.no-show-sweep}")
    public void sweepNoShows() {
        try {
            Integer n;
            do {
                n = tx.execute(s -> {
                    List<AppointmentRow> due = appointments.lockOverdue(clock.instant().minus(grace), 200);
                    due.forEach(a -> move(a, AppointmentStatus.NO_SHOW));
                    return due.size();
                });
                if (n != null && n > 0) {
                    meters.counter("careslot.no_shows").increment(n);
                }
            } while (n != null && n == 200);
        } catch (RuntimeException e) {
            log.warn("no-show sweep failed: {}", e.toString());
        }
    }
}
