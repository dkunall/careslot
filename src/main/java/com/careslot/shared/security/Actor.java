package com.careslot.shared.security;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Who is calling and in which roles. Staff tokens may carry several roles; patient tokens carry only PATIENT. */
public record Actor(String subject, Set<Role> roles) {

    public Actor {
        roles = roles.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(roles));
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    public boolean hasAny(Role... any) {
        return Arrays.stream(any).anyMatch(roles::contains);
    }

    public boolean staff() {
        return hasAny(Role.ADMIN, Role.FRONT_DESK, Role.NURSE, Role.DOCTOR);
    }

    public String rolesText() {
        return roles.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    public static Actor system(String component) {
        return new Actor("system:" + component, Set.of());
    }
}
