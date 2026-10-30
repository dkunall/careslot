package com.careslot.shared.security;

import com.careslot.shared.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves the caller from the JWT on the current request. */
public final class Actors {

    private Actors() {
    }

    public static Actor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt) || jwt.getToken().getSubject() == null) {
            throw new ApiException(401, "unauthenticated", "no caller");
        }
        Set<Role> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(s -> s.startsWith("SCOPE_")).map(s -> s.substring(6))
                .map(Role::fromScope).flatMap(Optional::stream).collect(Collectors.toSet());
        return new Actor(jwt.getToken().getSubject(), roles);
    }

    public static Actor requireAny(Role... roles) {
        Actor a = current();
        if (!a.hasAny(roles)) {
            throw ApiException.forbidden("forbidden", "requires one of " + Arrays.toString(roles));
        }
        return a;
    }
}
