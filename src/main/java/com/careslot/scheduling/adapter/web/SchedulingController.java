package com.careslot.scheduling.adapter.web;

import com.careslot.scheduling.application.AppointmentKind;
import com.careslot.scheduling.application.AppointmentRow;
import com.careslot.scheduling.application.AvailabilityService;
import com.careslot.scheduling.application.BookingCommand;
import com.careslot.scheduling.application.BookingService;
import com.careslot.scheduling.application.RosterService;
import com.careslot.scheduling.application.WaitlistService;
import com.careslot.scheduling.application.WaitlistStore;
import com.careslot.scheduling.domain.Interval;
import com.careslot.shared.error.ApiException;
import com.careslot.shared.security.Actors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class SchedulingController {

    record TemplateBody(@NotBlank @Size(max = 200) String rrule, @NotNull LocalTime dayStart, @NotNull LocalTime dayEnd,
                        @Min(5) @Max(240) int slotMinutes, @Min(1) @Max(50) int capacity, @NotNull LocalDate validFrom,
                        LocalDate validTo) {
        RosterService.TemplateSpec spec() {
            return new RosterService.TemplateSpec(rrule, dayStart, dayEnd, slotMinutes, capacity, validFrom, validTo);
        }
    }

    record ReplaceBody(@NotNull LocalDate effectiveFrom, @NotNull @Valid TemplateBody template) {
    }

    record LeaveBody(@NotNull Instant start, @NotNull Instant end, @NotBlank @Size(max = 200) String reason) {
    }

    record BookBody(@NotNull UUID patientId, @NotBlank String clinicId, AppointmentKind kind, UUID practitionerId,
                    @NotNull Instant from, @NotNull Instant to, String strategy, List<List<UUID>> needs) {
    }

    record RescheduleBody(@NotNull Instant from, @NotNull Instant to, String strategy, UUID practitionerId) {
    }

    record CancelBody(@Size(max = 200) String reason) {
    }

    record WaitBody(@NotNull UUID patientId, @NotNull UUID practitionerId, @NotNull Instant from, @NotNull Instant to,
                    Integer priority) {
    }

    record AppointmentJson(UUID id, UUID patientId, UUID practitionerId, String clinicId, String kind, Instant start,
                           Instant end, String status, boolean overbooked, UUID rescheduledFrom) {
        static AppointmentJson of(AppointmentRow a) {
            return new AppointmentJson(a.id(), a.patientId(), a.practitionerId(), a.clinicId(), a.kind().name(),
                    a.period().start(), a.period().end(), a.status().name(), a.overbooked(), a.rescheduledFrom());
        }
    }

    record DaySlotJson(UUID id, Instant start, Instant end, String status) {
    }

    record WaitJson(UUID id, UUID patientId, Instant from, Instant to, int priority, Instant createdAt, String status,
                    UUID appointmentId) {
        static WaitJson of(WaitlistStore.Row r) {
            var e = r.entry();
            return new WaitJson(e.id(), e.patientId(), e.window().start(), e.window().end(), e.priority(),
                    e.createdAt(), r.status(), r.appointmentId());
        }
    }

    private final RosterService roster;
    private final BookingService booking;
    private final AvailabilityService availability;
    private final WaitlistService waitlist;

    SchedulingController(RosterService roster, BookingService booking, AvailabilityService availability,
                         WaitlistService waitlist) {
        this.roster = roster;
        this.booking = booking;
        this.availability = availability;
        this.waitlist = waitlist;
    }

    private static Interval interval(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw ApiException.invalid("'to' must be after 'from'");
        }
        return new Interval(from, to);
    }

    @PostMapping("/v1/resources/{id}/templates")
    @ResponseStatus(HttpStatus.CREATED)
    RosterService.TemplateResult template(@PathVariable UUID id, @Valid @RequestBody TemplateBody body) {
        return roster.createTemplate(Actors.current(), id, body.spec());
    }

    @PostMapping("/v1/templates/{id}/replace")
    @ResponseStatus(HttpStatus.CREATED)
    RosterService.TemplateResult replace(@PathVariable UUID id, @Valid @RequestBody ReplaceBody body) {
        return roster.replaceTemplate(Actors.current(), id, body.effectiveFrom(), body.template().spec());
    }

    @PostMapping("/v1/resources/{id}/leaves")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Integer> leave(@PathVariable UUID id, @Valid @RequestBody LeaveBody body) {
        int blocked = roster.addLeave(Actors.current(), id, interval(body.start(), body.end()), body.reason());
        return Map.of("slotsBlocked", blocked);
    }

    @GetMapping("/v1/practitioners/{id}/availability")
    AvailabilityService.DayAvailability availability(@PathVariable UUID id, @RequestParam LocalDate date) {
        Actors.current();
        return availability.day(id, date);
    }

    @GetMapping("/v1/practitioners/{id}/appointments")
    List<DaySlotJson> day(@PathVariable UUID id, @RequestParam LocalDate date) {
        return booking.forPractitionerDay(Actors.current(), id, date).stream()
                .map(a -> new DaySlotJson(a.id(), a.period().start(), a.period().end(), a.status().name())).toList();
    }

    @PostMapping("/v1/appointments")
    ResponseEntity<AppointmentJson> book(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @Valid @RequestBody BookBody body) {
        BookingCommand cmd = new BookingCommand(body.patientId(), body.clinicId(),
                body.kind() == null ? AppointmentKind.CONSULT : body.kind(), body.practitionerId(),
                interval(body.from(), body.to()), body.strategy(), body.needs() == null ? List.of() : body.needs());
        BookingService.Result r = booking.book(Actors.current(), cmd, key);
        return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(r.replayed())).body(AppointmentJson.of(r.appointment()));
    }

    @PostMapping("/v1/appointments/{id}/reschedule")
    ResponseEntity<AppointmentJson> reschedule(@PathVariable UUID id,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody RescheduleBody body) {
        BookingService.Result r = booking.reschedule(Actors.current(), id, interval(body.from(), body.to()),
                body.strategy(), body.practitionerId(), key);
        return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(r.replayed())).body(AppointmentJson.of(r.appointment()));
    }

    @PostMapping("/v1/appointments/{id}/cancel")
    AppointmentJson cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelBody body) {
        return AppointmentJson.of(booking.cancel(Actors.current(), id, body == null ? null : body.reason()));
    }

    @GetMapping("/v1/appointments/{id}")
    AppointmentJson get(@PathVariable UUID id) {
        return AppointmentJson.of(booking.get(Actors.current(), id));
    }

    @GetMapping("/v1/patients/{id}/appointments")
    List<AppointmentJson> forPatient(@PathVariable UUID id) {
        return booking.forPatient(Actors.current(), id).stream().map(AppointmentJson::of).toList();
    }

    @PostMapping("/v1/waitlist")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> join(@Valid @RequestBody WaitBody body) {
        UUID id = waitlist.join(Actors.current(), body.patientId(), body.practitionerId(),
                interval(body.from(), body.to()), body.priority() == null ? 0 : body.priority());
        return Map.of("id", id);
    }

    @DeleteMapping("/v1/waitlist/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void withdraw(@PathVariable UUID id) {
        waitlist.withdraw(Actors.current(), id);
    }

    @GetMapping("/v1/practitioners/{id}/waitlist")
    List<WaitJson> waiting(@PathVariable UUID id) {
        return waitlist.forPractitioner(Actors.current(), id).stream().map(WaitJson::of).toList();
    }
}
