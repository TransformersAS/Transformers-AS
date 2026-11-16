package com.transformersas.marketplace.auth.infrastructure.security;

import com.transformersas.marketplace.users.domain.model.AccountStatus;
import com.transformersas.marketplace.users.domain.port.AccountRestrictionReader;
import com.transformersas.marketplace.users.domain.repository.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Revalidates the JDBC session identity before CSRF, logout and endpoint authorization. An active role under a
 * CU-22 restriction (on that role or on the whole account) is cleared: the session stays open, so the person can
 * still read and answer it from /api/account, but every endpoint of that role answers 403.
 */
public final class CurrentAccountSessionFilter extends OncePerRequestFilter {
    private final UserAccountRepository accounts;
    private final AccountRestrictionReader restrictions;
    private final SecurityContextRepository contexts;

    public CurrentAccountSessionFilter(UserAccountRepository accounts, AccountRestrictionReader restrictions,
                                       SecurityContextRepository contexts) {
        this.accounts = accounts;
        this.restrictions = restrictions;
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            var session = request.getSession(false);
            boolean expired = session == null || LoginSessionPolicy.isAbsolutelyExpired(
                    session.getAttribute(LoginSessionPolicy.AUTHENTICATED_AT),
                    session.getAttribute(LoginSessionPolicy.ABSOLUTE_EXPIRES_AT), java.time.Instant.now());
            var current = accounts.findById(principal.accountId());
            if (expired || current.isEmpty() || current.get().status() != AccountStatus.ACTIVA) {
                var logout = new SecurityContextLogoutHandler();
                logout.setSecurityContextRepository(contexts);
                logout.logout(request, response, authentication);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            var account = current.get();
            var effective = principal;
            if (!principal.roles().equals(account.roles()) || !principal.getUsername().equals(account.email())
                    || !principal.isEnabled()) {
                effective = principal.refresh(account);
            }
            if (effective.activeRole() != null && restrictions.isRestricted(account.id(), effective.activeRole())) {
                effective = effective.withoutActiveRole();
            }
            if (effective != principal) {
                // Replace instead of mutating the context shared by concurrent requests from this session.
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        effective, null, effective.getAuthorities()));
                SecurityContextHolder.setContext(context);
                contexts.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
