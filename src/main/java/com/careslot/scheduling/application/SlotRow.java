package com.careslot.scheduling.application;

import com.careslot.scheduling.domain.Interval;
import com.careslot.scheduling.domain.ResourceKind;

import java.util.UUID;

public record SlotRow(long id, UUID resourceId, ResourceKind kind, Interval period, int capacity, int booked,
                      boolean blocked) {
}
