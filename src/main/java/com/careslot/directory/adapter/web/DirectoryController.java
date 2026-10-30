package com.careslot.directory.adapter.web;

import com.careslot.directory.application.DirectoryService;
import com.careslot.directory.application.PatientView;
import com.careslot.scheduling.domain.ResourceKind;
import com.careslot.shared.security.Actors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
class DirectoryController {

    private static final String ID = "[a-z0-9][a-z0-9-]{1,39}";

    record NewFacility(@NotBlank @Pattern(regexp = ID) String id, @NotBlank @Size(max = 120) String name,
                       @NotBlank String zone) {
    }

    record NewClinic(@NotBlank @Pattern(regexp = ID) String id, @NotBlank String facilityId,
                     @NotBlank @Size(max = 120) String name) {
    }

    record NewResource(@NotBlank String facilityId, String clinicId, @NotNull ResourceKind kind,
                       @NotBlank @Size(max = 120) String name, @Size(max = 120) String loginSubject) {
    }

    record NewHoliday(@NotNull LocalDate day, @NotBlank @Size(max = 120) String name) {
    }

    record Attendance(@PositiveOrZero long attended, @PositiveOrZero long noShows) {
    }

    record NewPatient(@NotBlank String healthId, @NotBlank @Size(max = 120) String name,
                      @Size(max = 20) String phone, @Size(max = 120) String email,
                      @Min(1900) @Max(2100) Integer birthYear, @Size(max = 120) String loginSubject) {
    }

    record HealthIdQuery(@NotBlank String healthId) {
    }

    private final DirectoryService directory;

    DirectoryController(DirectoryService directory) {
        this.directory = directory;
    }

    @PostMapping("/v1/facilities")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> facility(@Valid @RequestBody NewFacility body) {
        directory.createFacility(Actors.current(), body.id(), body.name(), body.zone());
        return Map.of("id", body.id());
    }

    @PostMapping("/v1/facilities/{id}/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> holiday(@PathVariable String id, @Valid @RequestBody NewHoliday body) {
        directory.addHoliday(Actors.current(), id, body.day(), body.name());
        return Map.of("day", body.day().toString());
    }

    @PostMapping("/v1/clinics")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> clinic(@Valid @RequestBody NewClinic body) {
        directory.createClinic(Actors.current(), body.id(), body.facilityId(), body.name());
        return Map.of("id", body.id());
    }

    @PostMapping("/v1/clinics/{id}/attendance-history")
    Map<String, String> attendance(@PathVariable String id, @Valid @RequestBody Attendance body) {
        directory.importAttendance(Actors.current(), id, body.attended(), body.noShows());
        return Map.of("clinicId", id);
    }

    @PostMapping("/v1/resources")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> resource(@Valid @RequestBody NewResource body) {
        return Map.of("id", directory.createResource(Actors.current(), body.facilityId(), body.clinicId(), body.kind(),
                body.name(), body.loginSubject()));
    }

    @PostMapping("/v1/patients")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> patient(@Valid @RequestBody NewPatient body) {
        return Map.of("id", directory.registerPatient(Actors.current(), body.healthId(), body.name(), body.phone(),
                body.email(), body.birthYear(), body.loginSubject()));
    }

    @GetMapping("/v1/patients/{id}")
    PatientView patient(@PathVariable UUID id) {
        return directory.readPatient(Actors.current(), id);
    }

    /** POST so the health ID stays out of URLs and access logs. */
    @PostMapping("/v1/patients/search")
    Map<String, UUID> search(@Valid @RequestBody HealthIdQuery body) {
        return Map.of("id", directory.findByHealthId(Actors.current(), body.healthId()));
    }
}
