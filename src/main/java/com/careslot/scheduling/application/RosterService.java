package com.careslot.scheduling.application;

import com.careslot.directory.application.DirectoryService;
import com.careslot.directory.application.Resource;
import com.careslot.scheduling.domain.Interval;
import com.careslot.scheduling.domain.IntervalTree;
import com.careslot.scheduling.domain.RecurrenceRule;
import com.careslot.shared.domain.ResourceKind;
import com.careslot.scheduling.domain.ScheduleTemplate;
import com.careslot.scheduling.domain.SlotPlanner;
import com.careslot.shared.config.CareSlotProperties;
import com.careslot.shared.error.ApiException;
import com.careslot.shared.error.SqlStates;
import com.careslot.shared.security.Actor;
import com.careslot.shared.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rosters: templates become slots for a rolling horizon; template changes and leaves only ever touch the
 * future, and never silently drop a booked patient.
 */
@Service
public class RosterService {

    private static final Logger log = LoggerFactory.getLogger(RosterService.class);

    public record TemplateSpec(String rrule, LocalTime dayStart, LocalTime dayEnd, int slotMinutes, int capacity,
                               LocalDate validFrom, LocalDate validTo) {
    }

    public record TemplateResult(UUID templateId, int slotsCreated, int freeSlotsRemoved, int bookedSlotsKept) {
    }

    private final SlotStore slots;
    private final DirectoryService directory;
    private final AvailabilityCache cache;
    private final Clock clock;
    private final int horizonDays;

    RosterService(SlotStore slots, DirectoryService directory, AvailabilityCache cache, Clock clock,
                  CareSlotProperties props) {
        this.slots = slots;
        this.directory = directory;
        this.cache = cache;
        this.clock = clock;
        this.horizonDays = props.booking().horizonDays();
    }

    @Transactional
    public TemplateResult createTemplate(Actor actor, UUID resourceId, TemplateSpec spec) {
        requireAdmin(actor);
        Resource r = directory.resource(resourceId);
        ZoneId zone = directory.facility(r.facilityId()).zone();
        TemplateRow row = new TemplateRow(UUID.randomUUID(), resourceId, spec.rrule(), spec.dayStart(), spec.dayEnd(),
                spec.slotMinutes(), spec.capacity(), spec.validFrom(), spec.validTo(), false);
        ScheduleTemplate t = toDomain(row, zone);
        slots.insertTemplate(row);
        int created = generate(t, r);
        return new TemplateResult(row.id(), created, 0, 0);
    }

    /**
     * Replaces a template from {@code effectiveFrom} on. Free future slots of the old template go; booked ones
     * stay where they are (the patients keep their appointments) and the new template simply does not create
     * slots that would overlap them.
     */
    @Transactional
    public TemplateResult replaceTemplate(Actor actor, UUID oldTemplateId, LocalDate effectiveFrom, TemplateSpec spec) {
        requireAdmin(actor);
        TemplateRow old = slots.template(oldTemplateId).orElseThrow(() -> ApiException.notFound("template"));
        if (old.superseded()) {
            throw ApiException.conflict("template-superseded", "template was already replaced");
        }
        Resource r = directory.resource(old.resourceId());
        ZoneId zone = directory.facility(r.facilityId()).zone();
        if (effectiveFrom.isBefore(LocalDate.now(clock.withZone(zone)))) {
            throw ApiException.invalid("template changes apply to future dates only");
        }
        TemplateRow next = new TemplateRow(UUID.randomUUID(), old.resourceId(), spec.rrule(), spec.dayStart(),
                spec.dayEnd(), spec.slotMinutes(), spec.capacity(), effectiveFrom, spec.validTo(), false);
        ScheduleTemplate t = toDomain(next, zone);
        Instant cut = effectiveFrom.atStartOfDay(zone).toInstant();
        Instant now = clock.instant();
        Instant from = cut.isAfter(now) ? cut : now;
        slots.supersede(old.id(), effectiveFrom.minusDays(1));
        int removed = slots.deleteFreeSlotsFrom(old.id(), from);
        int kept = slots.countBookedSlotsFrom(old.id(), from);
        slots.insertTemplate(next);
        int created = generate(t, r);
        evictHorizon(r.id(), zone);
        return new TemplateResult(next.id(), created, removed, kept);
    }

    /** Leave for a doctor, room or machine. Refused with 409 if it would strand booked patients. */
    @Transactional
    public int addLeave(Actor actor, UUID resourceId, Interval period, String reason) {
        Resource r = directory.resource(resourceId);
        boolean own = r.kind() == ResourceKind.PRACTITIONER
                && directory.practitionerIdFor(actor).filter(resourceId::equals).isPresent();
        if (!actor.has(Role.ADMIN) && !own) {
            throw ApiException.forbidden("forbidden", "admin, or the doctor herself");
        }
        try {
            slots.insertLeave(UUID.randomUUID(), resourceId, period, reason, actor.subject());
        } catch (DataIntegrityViolationException e) {
            if (SqlStates.is(e, SqlStates.EXCLUSION_VIOLATION)) {
                throw ApiException.conflict("overlapping-leave", "overlaps an existing leave");
            }
            throw e;
        }
        List<SlotRow> affected = slots.lockSlotsOverlapping(resourceId, period);
        long booked = affected.stream().filter(s -> s.booked() > 0).count();
        if (booked > 0) {
            throw new ApiException(409, "leave-conflicts-bookings",
                    booked + " slot(s) in that period have patients; move them first",
                    Map.of("bookedSlots", booked));
        }
        slots.block(affected.stream().map(SlotRow::id).toList());
        evictHorizon(resourceId, directory.facility(r.facilityId()).zone());
        return affected.size();
    }

    /** Keeps the horizon filled. Idempotent: overlapping slots are skipped, so reruns create nothing new. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    public void extendHorizon() {
        for (TemplateRow row : slots.activeTemplates()) {
            try {
                Resource r = directory.resource(row.resourceId());
                generate(toDomain(row, directory.facility(r.facilityId()).zone()), r);
            } catch (RuntimeException e) {
                log.warn("horizon extension failed for template {}: {}", row.id(), e.toString());
            }
        }
    }

    private int generate(ScheduleTemplate t, Resource r) {
        LocalDate today = LocalDate.now(clock.withZone(t.zone()));
        LocalDate to = today.plusDays(horizonDays);
        Instant fromI = today.atStartOfDay(t.zone()).toInstant();
        Instant toI = to.plusDays(1).atStartOfDay(t.zone()).toInstant();
        IntervalTree<Interval> leaves = IntervalTree.of(slots.leaves(r.id(), fromI, toI), i -> i);
        Instant now = clock.instant();
        List<SlotPlanner.PlannedSlot> planned = SlotPlanner.plan(t, today, to,
                directory.holidays(r.facilityId(), today, to), leaves).stream()
                .filter(p -> p.period().start().isAfter(now)).toList();
        return slots.insertSlots(r.id(), t.id(), planned);
    }

    private void evictHorizon(UUID resourceId, ZoneId zone) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        for (int d = 0; d <= horizonDays; d++) {
            cache.evict(resourceId, today.plusDays(d));
        }
    }

    private static ScheduleTemplate toDomain(TemplateRow row, ZoneId zone) {
        try {
            return new ScheduleTemplate(row.id(), row.resourceId(), RecurrenceRule.parse(row.rrule()), row.dayStart(),
                    row.dayEnd(), row.slotMinutes(), row.capacity(), row.validFrom(), row.validTo(), zone);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    private static void requireAdmin(Actor actor) {
        if (!actor.has(Role.ADMIN)) {
            throw ApiException.forbidden("forbidden", "admin only");
        }
    }
}
