package com.careslot.scheduling.application;

import com.careslot.scheduling.domain.Interval;
import com.careslot.scheduling.domain.SlotPlanner;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for templates, slots and leaves. */
public interface SlotStore {

    void insertTemplate(TemplateRow t);

    Optional<TemplateRow> template(UUID id);

    List<TemplateRow> activeTemplates();

    /** Ends a template: it stops generating slots after {@code validTo}. */
    void supersede(UUID id, java.time.LocalDate validTo);

    /** Inserts slots, skipping any that would overlap an existing slot of the same resource. Returns inserted. */
    int insertSlots(UUID resourceId, UUID templateId, List<SlotPlanner.PlannedSlot> slots);

    /** Deletes the template's empty slots starting at or after {@code from}; returns how many. */
    int deleteFreeSlotsFrom(UUID templateId, Instant from);

    int countBookedSlotsFrom(UUID templateId, Instant from);

    List<Interval> leaves(UUID resourceId, Instant from, Instant to);

    /** Throws if it overlaps another leave of the same resource (exclusion constraint). */
    void insertLeave(UUID id, UUID resourceId, Interval period, String reason, String createdBy);

    List<SlotRow> lockSlotsOverlapping(UUID resourceId, Interval period);

    void block(Collection<Long> slotIds);

    List<SlotRow> slots(Collection<UUID> resourceIds, Interval window);

    /** The booking guard: {@code booked < limit and not blocked}, incremented atomically. Returns the new
     *  booked count, or -1 if the slot was full or blocked. */
    int tryReserve(long slotId, int limit);

    /** Gives back the slots an appointment still holds; returns how many. */
    int release(UUID appointmentId);

    List<Long> heldSlots(UUID appointmentId);
}
