package com.careslot.shared.security;

import java.util.Locale;
import java.util.Optional;

/** Hospital roles, carried as OAuth2 scopes {@code careslot.<role>}. */
public enum Role {
    ADMIN, FRONT_DESK, NURSE, DOCTOR, PATIENT;

    public String scope() {
        return "careslot." + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<Role> fromScope(String scope) {
        for (Role r : values()) {
            if (r.scope().equals(scope)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
