package com.careslot.scheduling.application;

import com.careslot.directory.application.Clinic;
import com.careslot.directory.application.DirectoryService;
import com.careslot.directory.application.Resource;
import com.careslot.scheduling.domain.AppointmentStatus;
import com.careslot.scheduling.domain.BookingOption;
import com.careslot.scheduling.domain.Interval;
import com.careslot.scheduling.domain.NoShowEstimate;
import com.careslot.scheduling.domain.OverbookingPolicy;
import com.careslot.shared.domain.ResourceKind;
import com.careslot.scheduling.domain.SlotAllocator;
import com.careslot.scheduling.domain.SlotRef;
import com.careslot.shared.config.CareSlotProperties;
import com.careslot.shared.error.ApiException;
import com.careslot.shared.error.SqlStates;
import com.careslot.shared.events.AppointmentEvent;
import com.careslot.shared.events.EventOutbox;
import com.careslot.shared.ratelimit.BookingRateLimiter;
import com.careslot.shared.security.Actor;
import com.careslot.shared.security.Role;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Books, cancels and reschedules. The guarantees come from the database, not from this class being careful:
 * <ul>
 *   <li>every slot is taken with {@code update ... where booked < limit}, so two requests can never both get
 *       the last place, whatever the interleaving;</li>
 *   <li>a multi-resource booking locks its slots in ascending id order, so two bookings wanting the same room
 *       and doctor in opposite orders cannot deadlock;</li>
 *   <li>each candidate is tried inside a savepoint: either all of its slots are taken or none are;</li>
 *   <li>an exclusion constraint stops a patient holding two overlapping live appointments;</li>
 *   <li>requests sharing an idempotency key are serialised with an advisory lock and replayed.</li>
 * </ul>
 */
@Service
public class BookingService {

    public sealed interface Outcome {
        record Booked(AppointmentRow appointment) implements Outcome {
        }

        record Replayed(AppointmentRow appointment) implements Outcome {
        }

        record NoCapacity(int optionsTried) implements Outcome {
        }

        record PatientBusy() implements Outcome {
        }
    }

    public record Result(AppointmentRow appointment, boolean replayed) {
    }

    private final SlotStore slots;
    private final AppointmentStore appointments;
    private final DirectoryService directory;
    private final EventOutbox outbox;
    private final BookingRateLimiter rateLimiter;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;
    private final CareSlotProperties.Booking cfg;
    private final CareSlotProperties.Reminders reminders;
    private final OverbookingPolicy overbooking;

    BookingService(SlotStore slots, AppointmentStore appointments, DirectoryService directory, EventOutbox outbox,
                   BookingRateLimiter rateLimiter, TransactionTemplate tx, Clock clock, MeterRegistry meters,
                   CareSlotProperties props) {
        this.slots = slots;
        this.appointments = appointments;
        this.directory = directory;
        this.outbox = outbox;
        this.rateLimiter = rateLimiter;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
        this.cfg = props.booking();
        this.reminders = props.reminders();
        this.overbooking = new OverbookingPolicy.BinomialNoShow(cfg.maxOverflowProbability(), cfg.maxExtraFraction());
    }

    public Result book(Actor actor, BookingCommand cmd, String idempotencyKey) {
        authorise(actor, cmd.patientId());
        requireKey(idempotencyKey);
        rateLimiter.check(cmd.patientId());
        Timer.Sample sample = Timer.start(meters);
        Outcome outcome = tx.execute(status -> attempt(status, cmd, "book:" + cmd.patientId(), idempotencyKey,
                actor.subject(), null));
        sample.stop(meters.timer("careslot.booking", "outcome", outcome.getClass().getSimpleName()));
        return toResult(outcome);
    }

    /**
     * Moves a consult: the old appointment is cancelled and its slots released, then the new one is booked, in
     * one transaction. If nothing is free the whole thing rolls back and the patient keeps the old time.
     */
    public Result reschedule(Actor actor, UUID appointmentId, Interval window, String strategy, UUID practitionerId,
                             String idempotencyKey) {
        requireKey(idempotencyKey);
        AppointmentRow current = appointments.find(appointmentId).orElseThrow(() -> ApiException.notFound("appointment"));
        authorise(actor, current.patientId());
        if (current.kind() != AppointmentKind.CONSULT) {
            throw ApiException.conflict("not-reschedulable", "procedures are cancelled and rebooked, not moved");
        }
        rateLimiter.check(current.patientId());
        String scope = "reschedule:" + appointmentId;
        BookingCommand cmd = new BookingCommand(current.patientId(), current.clinicId(), current.kind(),
                practitionerId, window, strategy, List.of());
        Outcome outcome = tx.execute(status -> {
            appointments.lockIdempotencyKey(scope, idempotencyKey);
            Optional<AppointmentRow> prior = appointments.byIdempotencyKey(scope, idempotencyKey);
            if (prior.isPresent()) {
                return replay(prior.get(), cmd);
            }
            AppointmentRow old = appointments.lock(appointmentId).orElseThrow();
            if (!old.status().canMoveTo(AppointmentStatus.CANCELLED)) {
                throw ApiException.conflict("invalid-state", "a " + old.status() + " appointment cannot be moved");
            }
            releaseAndCancel(old, "rescheduled", AppointmentEvent.Type.RESCHEDULED_AWAY);
            Outcome o = attempt(status, cmd, scope, idempotencyKey, actor.subject(), old.id());
            if (!(o instanceof Outcome.Booked)) {
                status.setRollbackOnly();
            }
            return o;
        });
        return toResult(outcome);
    }

    public AppointmentRow cancel(Actor actor, UUID appointmentId, String reason) {
        AppointmentRow a = appointments.find(appointmentId).orElseThrow(() -> ApiException.notFound("appointment"));
        authorise(actor, a.patientId());
        return tx.execute(status -> {
            AppointmentRow locked = appointments.lock(appointmentId).orElseThrow();
            if (locked.status() == AppointmentStatus.CANCELLED) {
                return locked; // cancelling twice is not an error
            }
            if (!locked.status().canMoveTo(AppointmentStatus.CANCELLED)) {
                throw ApiException.conflict("invalid-state", "a " + locked.status() + " appointment cannot be cancelled");
            }
            releaseAndCancel(locked, reason == null || reason.isBlank() ? "cancelled" : reason,
                    AppointmentEvent.Type.CANCELLED);
            return appointments.find(appointmentId).orElseThrow();
        });
    }

    public AppointmentRow get(Actor actor, UUID id) {
        AppointmentRow a = appointments.find(id).orElseThrow(() -> ApiException.notFound("appointment"));
        authoriseRead(actor, a);
        return a;
    }

    public List<AppointmentRow> forPatient(Actor actor, UUID patientId) {
        directory.authorisePatientAccess(actor, patientId);
        return appointments.forPatient(patientId);
    }

    /** One doctor's day for staff screens: ids, times and status, no patient details. */
    public List<AppointmentRow> forPractitionerDay(Actor actor, UUID practitionerId, java.time.LocalDate day) {
        if (!actor.staff()) {
            throw ApiException.forbidden("forbidden", "staff only");
        }
        java.time.ZoneId zone = directory.practitionerZone(practitionerId)
                .orElseThrow(() -> ApiException.notFound("practitioner"));
        return appointments.forPractitioner(practitionerId,
                new Interval(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant()));
    }

    /**
     * The booking itself, run inside the caller's transaction. Used by {@link #book}, {@link #reschedule}
     * and the waitlist matcher, which needs the {@link Outcome} rather than an exception.
     */
    Outcome attempt(TransactionStatus status, BookingCommand cmd, String scope, String key, String createdBy,
                    UUID rescheduledFrom) {
        appointments.lockIdempotencyKey(scope, key);
        Optional<AppointmentRow> prior = appointments.byIdempotencyKey(scope, key);
        if (prior.isPresent()) {
            return replay(prior.get(), cmd);
        }
        Clinic clinic = directory.clinic(cmd.clinicId());
        ZoneId zone = directory.facility(clinic.facilityId()).zone();
        Candidates candidates = options(cmd, clinic, zone);
        SlotAllocator allocator;
        try {
            allocator = SlotAllocator.named(cmd.strategy(), cmd.practitionerId());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
        List<BookingOption> feasible = allocator.rank(candidates.options());
        List<BookingOption> ranked = feasible.stream()
                .filter(o -> !appointments.patientBusy(cmd.patientId(), o.period()))
                .limit(cfg.maxAttempts()).toList();
        if (ranked.isEmpty() && !feasible.isEmpty()) {
            return new Outcome.PatientBusy();
        }
        int tried = 0;
        for (BookingOption option : ranked) {
            tried++;
            meters.counter("careslot.booking.attempts").increment();
            Object savepoint = status.createSavepoint();
            boolean overbooked = false;
            boolean all = true;
            for (SlotRef s : option.lockOrder()) {
                int booked = slots.tryReserve(s.id(), s.limit());
                if (booked < 0) {
                    all = false;
                    break;
                }
                if (s.kind() == ResourceKind.PRACTITIONER && booked > candidates.capacity(s.id())) {
                    overbooked = true;
                }
            }
            if (!all) {
                status.rollbackToSavepoint(savepoint);
                status.releaseSavepoint(savepoint);
                continue;
            }
            AppointmentRow row = new AppointmentRow(UUID.randomUUID(), cmd.patientId(), option.practitionerId(),
                    cmd.clinicId(), cmd.kind(), option.period(), AppointmentStatus.BOOKED, overbooked, scope, key,
                    cmd.fingerprint(), rescheduledFrom, createdBy, 0);
            try {
                appointments.insert(row);
            } catch (DataIntegrityViolationException e) {
                if (SqlStates.is(e, SqlStates.EXCLUSION_VIOLATION)) {
                    status.rollbackToSavepoint(savepoint);
                    status.releaseSavepoint(savepoint);
                    return new Outcome.PatientBusy();
                }
                throw e;
            }
            status.releaseSavepoint(savepoint);
            appointments.linkSlots(row.id(), option.slots().stream().map(SlotRef::id).toList());
            scheduleReminders(row);
            outbox.append(Events.of(AppointmentEvent.Type.BOOKED, row));
            if (overbooked) {
                meters.counter("careslot.booking.overbooked").increment();
            }
            return new Outcome.Booked(row);
        }
        return new Outcome.NoCapacity(tried);
    }

    /** Options plus each slot's real capacity, to tell an overbooked place from a normal one. */
    private record Candidates(List<BookingOption> options, Map<Long, Integer> capacities) {
        int capacity(long slotId) {
            return capacities.getOrDefault(slotId, Integer.MAX_VALUE);
        }
    }

    /** Every feasible (doctor slot, one slot per need) combination in the window. */
    private Candidates options(BookingCommand cmd, Clinic clinic, ZoneId zone) {
        List<UUID> practitioners;
        if (cmd.practitionerId() != null) {
            Resource r = directory.resource(cmd.practitionerId());
            if (r.kind() != ResourceKind.PRACTITIONER || !clinic.id().equals(r.clinicId())) {
                throw ApiException.invalid("practitioner is not in this clinic");
            }
            practitioners = List.of(r.id());
        } else {
            practitioners = directory.practitioners(clinic.id()).stream().map(Resource::id).toList();
        }
        Set<UUID> needIds = new LinkedHashSet<>();
        for (List<UUID> group : cmd.needs()) {
            for (UUID id : group) {
                Resource r = directory.resource(id);
                if (r.kind() == ResourceKind.PRACTITIONER || !r.facilityId().equals(clinic.facilityId())) {
                    throw ApiException.invalid("need " + id + " is not a room or equipment in this facility");
                }
                needIds.add(id);
            }
        }
        Instant now = clock.instant();
        List<SlotRow> doctorSlots = slots.slots(practitioners, cmd.window()).stream()
                .filter(s -> s.period().start().isAfter(now) && !s.blocked()).toList();
        Map<UUID, List<SlotRow>> needSlots = new HashMap<>();
        if (!needIds.isEmpty()) {
            for (SlotRow s : slots.slots(needIds, cmd.window())) {
                if (!s.blocked() && s.booked() < s.capacity()) {
                    needSlots.computeIfAbsent(s.resourceId(), k -> new ArrayList<>()).add(s);
                }
            }
        }
        NoShowEstimate estimate = appointments.attendance(clinic.id(), cfg.minObservations());
        Map<String, Integer> loads = new HashMap<>();
        Map<Long, Integer> caps = new HashMap<>();
        List<BookingOption> out = new ArrayList<>();
        for (SlotRow d : doctorSlots) {
            int limit = cmd.kind() == AppointmentKind.CONSULT ? overbooking.bookingLimit(d.capacity(), estimate)
                    : d.capacity();
            if (d.booked() >= limit) {
                continue;
            }
            SlotRef doctor = new SlotRef(d.id(), d.resourceId(), ResourceKind.PRACTITIONER, d.period(), d.booked(), limit);
            caps.put(d.id(), d.capacity());
            List<List<SlotRow>> groups = new ArrayList<>();
            for (List<UUID> group : cmd.needs()) {
                groups.add(group.stream()
                        .flatMap(id -> needSlots.getOrDefault(id, List.of()).stream())
                        .filter(s -> s.period().contains(d.period()))
                        .sorted(Comparator.comparingInt(SlotRow::booked).thenComparingLong(SlotRow::id))
                        .limit(PER_GROUP).toList());
            }
            LocalDate day = d.period().start().atZone(zone).toLocalDate();
            int load = loads.computeIfAbsent(d.resourceId() + "/" + day,
                    k -> appointments.liveCount(d.resourceId(), day, zone));
            for (List<SlotRow> combo : combinations(groups)) {
                List<SlotRef> refs = new ArrayList<>();
                refs.add(doctor);
                for (SlotRow s : combo) {
                    refs.add(new SlotRef(s.id(), s.resourceId(), s.kind(), s.period(), s.booked(), s.capacity()));
                    caps.put(s.id(), s.capacity());
                }
                out.add(new BookingOption(d.resourceId(), d.period(), refs, load));
            }
        }
        return new Candidates(out, caps);
    }

    /** Room/equipment candidates kept per need group, and resource combinations offered per doctor slot. */
    static final int PER_GROUP = 3;
    static final int COMBOS_PER_SLOT = 4;

    /**
     * Combinations of one slot per need group, least-booked first (ordered by the sum of each pick's rank), at
     * most {@link #COMBOS_PER_SLOT}. Offering more than one combination matters under contention: when two
     * requests both go for the emptiest room, the loser falls back to the other room instead of giving up the
     * doctor slot. A combination that uses one slot twice is skipped.
     */
    static List<List<SlotRow>> combinations(List<List<SlotRow>> groups) {
        List<int[]> picks = new ArrayList<>();
        picks.add(new int[groups.size()]);
        for (int g = 0; g < groups.size(); g++) {
            int size = groups.get(g).size();
            if (size == 0) {
                return List.of();
            }
            List<int[]> next = new ArrayList<>();
            for (int[] p : picks) {
                for (int i = 0; i < size; i++) {
                    int[] q = p.clone();
                    q[g] = i;
                    next.add(q);
                }
            }
            picks = next;
        }
        picks.sort(Comparator.comparingInt((int[] p) -> java.util.Arrays.stream(p).sum()));
        List<List<SlotRow>> out = new ArrayList<>();
        for (int[] p : picks) {
            List<SlotRow> combo = new ArrayList<>();
            for (int g = 0; g < p.length; g++) {
                combo.add(groups.get(g).get(p[g]));
            }
            if (combo.stream().map(SlotRow::id).distinct().count() == combo.size()) {
                out.add(combo);
            }
            if (out.size() == COMBOS_PER_SLOT) {
                break;
            }
        }
        return out;
    }

    private Outcome replay(AppointmentRow prior, BookingCommand cmd) {
        if (!prior.requestHash().equals(cmd.fingerprint())) {
            throw new ApiException(422, "idempotency-key-reused", "this Idempotency-Key was used for a different request");
        }
        return new Outcome.Replayed(prior);
    }

    void releaseAndCancel(AppointmentRow a, String reason, AppointmentEvent.Type type) {
        slots.release(a.id());
        appointments.updateStatus(a.id(), AppointmentStatus.CANCELLED, reason);
        appointments.cancelReminders(a.id());
        outbox.append(Events.of(type, a));
    }

    private void scheduleReminders(AppointmentRow a) {
        Instant now = clock.instant();
        for (Map.Entry<String, Duration> r : Map.of("T-" + reminders.before1().toHours() + "H", reminders.before1(),
                "T-" + reminders.before2().toHours() + "H", reminders.before2()).entrySet()) {
            Instant due = a.period().start().minus(r.getValue());
            if (due.isAfter(now)) {
                appointments.scheduleReminder(a.id(), r.getKey(), due);
            }
        }
    }

    private Result toResult(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Booked b -> new Result(b.appointment(), false);
            case Outcome.Replayed r -> new Result(r.appointment(), true);
            case Outcome.NoCapacity n -> throw new ApiException(409, "no-capacity",
                    "no free slot in that window", Map.of("optionsTried", n.optionsTried()));
            case Outcome.PatientBusy p -> throw ApiException.conflict("patient-busy",
                    "the patient already has an appointment at that time");
        };
    }

    private void authorise(Actor actor, UUID patientId) {
        if (actor.hasAny(Role.FRONT_DESK, Role.ADMIN)) {
            return;
        }
        if (actor.has(Role.PATIENT) && directory.patientIdFor(actor).filter(patientId::equals).isPresent()) {
            return;
        }
        throw ApiException.forbidden("forbidden", "front desk, or the patient herself");
    }

    private void authoriseRead(Actor actor, AppointmentRow a) {
        if (actor.hasAny(Role.FRONT_DESK, Role.ADMIN, Role.NURSE)) {
            return;
        }
        if (actor.has(Role.DOCTOR)) {
            if (directory.practitionerIdFor(actor).filter(a.practitionerId()::equals).isPresent()) {
                return;
            }
            throw ApiException.forbidden("forbidden", "doctors see their own appointments");
        }
        directory.authorisePatientAccess(actor, a.patientId());
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw ApiException.invalid("Idempotency-Key header (1..100 chars) is required");
        }
    }
}
