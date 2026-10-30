package com.careslot.directory.application;

import com.careslot.shared.domain.ResourceKind;

import java.util.UUID;

public record Resource(UUID id, String facilityId, String clinicId, ResourceKind kind, String name, String loginSubject) {
}
