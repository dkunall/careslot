package com.careslot.scheduling.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record TemplateRow(UUID id, UUID resourceId, String rrule, LocalTime dayStart, LocalTime dayEnd,
                          int slotMinutes, int capacity, LocalDate validFrom, LocalDate validTo, boolean superseded) {
}
