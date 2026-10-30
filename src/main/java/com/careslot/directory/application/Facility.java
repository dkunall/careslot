package com.careslot.directory.application;

import java.time.ZoneId;

public record Facility(String id, String name, ZoneId zone) {
}
