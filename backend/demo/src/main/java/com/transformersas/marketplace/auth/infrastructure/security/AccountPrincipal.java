package com.transformersas.marketplace.auth.infrastructure.security;

import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.model.Role;
import com.transformersas.marketplace.users.domain.model.UserAccount;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.io.Serial;
import java.util.List;
import java.util.Set;

/** Serializable session identity; credentials are erased by the authentication provider. */
public final class AccountPrincipal extends User {
    @Serial private static final long serialVersionUID = 1L;
    private final Long accountId;
    private final Set<Role> roles;
    private final Role activeRole;

    public AccountPrincipal(UserAccount account) {
        this(account.id(), account.email(), account.passwordHash(), account.status() == AccountStatus.ACTIVA,
                account.roles(), account.roles().size() == 1 ? account.roles().iterator().next() : null);
    }

    private AccountPrincipal(Long accountId, String email, String password, boolean enabled,
                             Set<Role> roles, Role activeRole) {
        super(email, password, enabled, true, true, true,
                activeRole == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + activeRole.name())));
        this.accountId = accountId;
        this.roles = Set.copyOf(roles);
        this.activeRole = activeRole;
    }

    public Long accountId() { return accountId; }
    public Set<Role> roles() { return roles; }
    public Role activeRole() { return activeRole; }

    /** Refresh an existing session without applying the single-role default used at login. */
    public AccountPrincipal refresh(UserAccount account) {
        Role retained = activeRole != null && account.roles().contains(activeRole) ? activeRole : null;
        var refreshed = new AccountPrincipal(account.id(), account.email(), "", account.status() == AccountStatus.ACTIVA,
                account.roles(), retained);
        refreshed.eraseCredentials();
        return refreshed;
    }

    public AccountPrincipal withActiveRole(Role role) {
        if (role == null || !roles.contains(role)) throw new IllegalArgumentException("Rol no disponible");
        var selected = new AccountPrincipal(accountId, getUsername(), "", isEnabled(), roles, role);
        selected.eraseCredentials();
        return selected;
    }
}
