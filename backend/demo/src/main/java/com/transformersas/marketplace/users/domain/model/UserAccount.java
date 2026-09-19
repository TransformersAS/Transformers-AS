package com.transformersas.marketplace.users.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Persistence model of an account; accepts an encoded password, never raw credentials. */
public record UserAccount(Long id, String email, String passwordHash,
                          AccountStatus status, Set<Role> roles) {
    public UserAccount {
        email = Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
        if (email.isBlank() || email.length() > 254) {
            throw new IllegalArgumentException("Email vacío o demasiado largo");
        }
        if (passwordHash == null || !passwordHash.matches(
                "[$]2[aby][$](0[4-9]|[12][0-9]|3[01])[$][./A-Za-z0-9]{53}")) {
            throw new IllegalArgumentException("Se requiere un hash BCrypt");
        }
        Objects.requireNonNull(status, "status");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }

    @Override
    public String toString() {
        return "UserAccount[id=" + id + ", status=" + status + ", roles=" + roles + "]";
    }
}
