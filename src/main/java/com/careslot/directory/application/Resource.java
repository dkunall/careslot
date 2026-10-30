package com.careslot.directory.application;

import com.careslot.scheduling.domain.ResourceKind;

import java.util.UUID;

public record Resource(UUID id, String facilityId, String clinicId, ResourceKind kind, String name, String loginSubject) {
}
