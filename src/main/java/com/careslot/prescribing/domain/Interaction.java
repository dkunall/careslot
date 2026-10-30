package com.careslot.prescribing.domain;

public record Interaction(String drugA, String drugB, Severity severity, String note) {
}
