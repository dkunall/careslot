package com.careslot.queue.application;

import com.careslot.directory.application.DirectoryService;
import com.careslot.queue.domain.ConsultDuration;
import com.careslot.queue.domain.QueueEta;
import com.careslot.scheduling.application.AppointmentLifecycle;
import com.careslot.scheduling.application.AppointmentRow;
import com.careslot.scheduling.domain.AppointmentStatus;
import com.careslot.shared.audit.PhiAudit;
import com.careslot.shared.config.CareSlotProperties;
import com.careslot.shared.crypto.PhiCipher;
import com.careslot.shared.error.ApiException;
import com.careslot.shared.security.Actor;
import com.careslot.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * OPD queue: check-in hands out a per-doctor, per-day token number; the doctor calls the next waiting token
 * and completes the consult, which feeds the EWMA of consult length that drives the waiting-room ETA.
 */
@Service
public class QueueService {

    /** How early before the appointment a patient may check in. */
    static final Duration EARLY_CHECK_IN = Duration.ofHours(2);
    static final String NOTE_COLUMN = "consult_note.note_enc";

    public record CheckIn(QueueToken token, boolean created) {
    }

    public record Eta(UUID appointmentId, int number, String status, int ahead, long etaSeconds, long meanConsultSeconds,
                      long consultSamples) {
    }

    public record Board(UUID practitionerId, LocalDate day, long meanConsultSeconds, long consultSamples,
                        List<Eta> tokens) {
    }

    private final QueueStore store;
    private final AppointmentLifecycle lifecycle;
    private final DirectoryService directory;
    private final PhiCipher cipher;
    private final PhiAudit audit;
    private final Clock clock;
    private final Duration defaultConsult;
    private final double alpha;
    private final Duration grace;

    QueueService(QueueStore store, AppointmentLifecycle lifecycle, DirectoryService directory, PhiCipher cipher,
                 PhiAudit audit, Clock clock, CareSlotProperties props) {
        this.store = store;
        this.lifecycle = lifecycle;
        this.directory = directory;
        this.cipher = cipher;
        this.audit = audit;
        this.clock = clock;
        this.defaultConsult = props.queue().defaultConsult();
        this.alpha = props.queue().alpha();
        this.grace = props.booking().noShowGrace();
    }

    /** Idempotent: checking in an appointment that already has a token returns that token. */
    @Transactional
    public CheckIn checkIn(Actor actor, UUID appointmentId) {
        if (!actor.hasAny(Role.ADMIN, Role.FRONT_DESK, Role.NURSE)) {
            throw ApiException.forbidden("forbidden", "check-in is done at the desk");
        }
        AppointmentRow a = lifecycle.lock(appointmentId);
        Optional<QueueToken> existing = store.forAppointment(appointmentId);
        if (existing.isPresent()) {
            return new CheckIn(existing.get(), false);
        }
        if (a.status() != AppointmentStatus.BOOKED) {
            throw ApiException.conflict("invalid-state", "a " + a.status() + " appointment cannot be checked in");
        }
        Instant now = clock.instant();
        if (now.isBefore(a.period().start().minus(EARLY_CHECK_IN)) || now.isAfter(a.period().end().plus(grace))) {
            throw ApiException.conflict("outside-check-in-window",
                    "check-in opens 2h before the appointment and closes at the no-show grace period");
        }
        LocalDate day = a.period().start().atZone(directory.zoneOf(a.practitionerId())).toLocalDate();
        int number = store.nextNumber(a.practitionerId(), day);
        QueueToken t = store.insert(new QueueToken(UUID.randomUUID(), a.id(), a.practitionerId(), day, number, "WAITING",
                now, null, null));
        lifecycle.move(a, AppointmentStatus.CHECKED_IN);
        return new CheckIn(t, true);
    }

    /** Empty when nobody is waiting; 409 while the doctor still has a patient called in. */
    @Transactional
    public Optional<QueueToken> callNext(Actor actor, UUID practitionerId) {
        authoriseDoctorOrNurse(actor, practitionerId);
        LocalDate day = clock.instant().atZone(directory.zoneOf(practitionerId)).toLocalDate();
        if (!store.lockDay(practitionerId, day)) {
            return Optional.empty();
        }
        if (store.called(practitionerId, day).isPresent()) {
            throw ApiException.conflict("consult-in-progress", "complete the current consult before calling the next");
        }
        Optional<QueueToken> next = store.firstWaiting(practitionerId, day);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        store.markCalled(next.get().id(), now);
        lifecycle.move(lifecycle.lock(next.get().appointmentId()), AppointmentStatus.IN_CONSULT);
        return store.forAppointment(next.get().appointmentId());
    }

    /** Only the treating doctor completes a consult. The optional note is stored encrypted and the write audited. */
    @Transactional
    public QueueToken complete(Actor actor, UUID appointmentId, String note) {
        AppointmentRow a = lifecycle.lock(appointmentId);
        if (!actor.has(Role.DOCTOR) || directory.practitionerIdFor(actor).filter(a.practitionerId()::equals).isEmpty()) {
            throw ApiException.forbidden("forbidden", "only the treating doctor completes a consult");
        }
        QueueToken t = store.forAppointment(appointmentId)
                .filter(x -> "CALLED".equals(x.status()))
                .orElseThrow(() -> ApiException.conflict("invalid-state", "this patient has not been called in"));
        Instant now = clock.instant();
        store.markDone(t.id(), now);
        ConsultDuration before = store.lockStats(a.practitionerId()).orElse(ConsultDuration.initial(defaultConsult));
        store.saveStats(a.practitionerId(), before.observe(Duration.between(t.calledAt(), now), alpha));
        if (note != null && !note.isBlank()) {
            store.saveNote(appointmentId, cipher.encrypt(note, NOTE_COLUMN), actor.subject());
            audit.record(actor, a.patientId(), "consult-note:write");
        }
        lifecycle.move(a, AppointmentStatus.COMPLETED);
        return store.forAppointment(appointmentId).orElseThrow();
    }

    /** Waiting-room board: token numbers and ETAs only, no names. */
    @Transactional(readOnly = true)
    public Board board(Actor actor, UUID practitionerId, LocalDate day) {
        if (!actor.staff()) {
            throw ApiException.forbidden("forbidden", "staff only");
        }
        directory.resource(practitionerId);
        return compute(practitionerId, day);
    }

    @Transactional(readOnly = true)
    public Eta eta(Actor actor, UUID appointmentId, UUID patientId) {
        directory.authorisePatientAccess(actor, patientId);
        QueueToken t = store.forAppointment(appointmentId).orElseThrow(() -> ApiException.notFound("queue token"));
        return compute(t.practitionerId(), t.day()).tokens().stream()
                .filter(e -> e.appointmentId().equals(appointmentId)).findFirst().orElseThrow();
    }

    private Board compute(UUID practitionerId, LocalDate day) {
        ConsultDuration avg = store.stats(practitionerId).orElse(ConsultDuration.initial(defaultConsult));
        List<QueueToken> tokens = store.day(practitionerId, day);
        Instant now = clock.instant();
        Duration elapsed = tokens.stream().filter(t -> "CALLED".equals(t.status())).findFirst()
                .map(t -> Duration.between(t.calledAt(), now)).orElse(null);
        List<Eta> out = new ArrayList<>();
        int ahead = 0;
        for (QueueToken t : tokens) {
            long eta = 0;
            int before = 0;
            if ("WAITING".equals(t.status())) {
                before = ahead;
                eta = QueueEta.estimate(ahead, avg, elapsed).toSeconds();
                ahead++;
            }
            out.add(new Eta(t.appointmentId(), t.number(), t.status(), before, eta, avg.mean().toSeconds(), avg.samples()));
        }
        return new Board(practitionerId, day, avg.mean().toSeconds(), avg.samples(), out);
    }

    private void authoriseDoctorOrNurse(Actor actor, UUID practitionerId) {
        if (actor.hasAny(Role.ADMIN, Role.NURSE)) {
            directory.resource(practitionerId);
            return;
        }
        if (actor.has(Role.DOCTOR) && directory.practitionerIdFor(actor).filter(practitionerId::equals).isPresent()) {
            return;
        }
        throw ApiException.forbidden("forbidden", "only this doctor or a nurse calls the queue");
    }
}
