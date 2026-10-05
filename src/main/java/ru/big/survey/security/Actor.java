package ru.big.survey.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import ru.big.survey.domain.Role;

/** Текущий пользователь запроса: логин и роли. */
public record Actor(String username, Set<Role> roles) {

    public static Actor of(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return new Actor("anonymous", Set.of());
        }
        Set<Role> roles = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .flatMap(a -> {
                    try {
                        return java.util.stream.Stream.of(Role.valueOf(a));
                    } catch (IllegalArgumentException e) {
                        return java.util.stream.Stream.empty();
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
        return new Actor(authentication.getName(), roles);
    }

    public boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }
}
