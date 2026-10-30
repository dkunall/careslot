package com.careslot.notify.application;

import com.careslot.directory.application.DirectoryService;
import com.careslot.directory.application.Facility;
import com.careslot.directory.application.PatientView;
import com.careslot.shared.config.CareSlotProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Sends due appointment reminders. Each batch is claimed with {@code FOR UPDATE SKIP LOCKED}, so several app
 * instances (or overlapping runs) never send the same reminder twice; the batch size is capped by
 * {@code per-second x poll} to respect the gateway's rate. The text carries only the facility and the local
 * time, never the doctor, clinic or reason for the visit, because SMS previews show on lock screens.
 */
@Component
public class ReminderWorker {

    private static final Logger log = LoggerFactory.getLogger(ReminderWorker.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    private final ReminderStore store;
    private final NotificationGateway gateway;
    private final DirectoryService directory;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;
    private final CareSlotProperties.Reminders props;

    ReminderWorker(ReminderStore store, NotificationGateway gateway, DirectoryService directory, TransactionTemplate tx,
                   Clock clock, MeterRegistry meters, CareSlotProperties props) {
        this.store = store;
        this.gateway = gateway;
        this.directory = directory;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
        this.props = props.reminders();
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "${careslot.reminders.poll}")
    public void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("reminder run failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One batch; returns how many reminders were sent. */
    public int runOnce() {
        int limit = (int) Math.max(1, props.perSecond() * Math.max(1, props.poll().toMillis()) / 1000);
        Integer sent = tx.execute(s -> {
            store.cancelStale();
            int n = 0;
            for (ReminderStore.Due d : store.claimDue(clock.instant(), limit)) {
                if (deliver(d, s)) {
                    n++;
                }
            }
            return n;
        });
        return sent == null ? 0 : sent;
    }

    private boolean deliver(ReminderStore.Due d, TransactionStatus status) {
        PatientView p = directory.patientInternal(d.patientId()).orElse(null);
        if (p == null || (p.phone() == null && p.email() == null)) {
            store.markFailed(d.id(), true);
            return false;
        }
        Facility f = directory.facility(directory.resource(d.practitionerId()).facilityId());
        String body = "CareSlot: reminder of your appointment at " + f.name() + " on "
                + WHEN.format(d.start().atZone(f.zone())) + " (" + f.zone().getId() + "). Reply STOP to opt out.";
        boolean sms = p.phone() != null;
        // a savepoint, so one failed send (e.g. a constraint in the gateway) does not abort the whole batch
        Object savepoint = status.createSavepoint();
        try {
            gateway.send(d.id(), sms ? "SMS" : "EMAIL", sms ? Masking.phone(p.phone()) : Masking.email(p.email()), body);
            status.releaseSavepoint(savepoint);
        } catch (RuntimeException e) {
            status.rollbackToSavepoint(savepoint);
            boolean giveUp = d.attempts() + 1 >= props.maxAttempts();
            store.markFailed(d.id(), giveUp);
            meters.counter("careslot.reminders", "result", giveUp ? "failed" : "retry").increment();
            return false;
        }
        store.markSent(d.id(), clock.instant());
        meters.counter("careslot.reminders", "result", "sent").increment();
        return true;
    }

}
