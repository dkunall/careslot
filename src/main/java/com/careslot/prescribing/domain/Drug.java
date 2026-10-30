package com.careslot.prescribing.domain;

/** A catalog entry. {@code therapeuticClass} drives duplicate-therapy and class-allergy checks. */
public record Drug(String code, String name, String therapeuticClass) {
}
