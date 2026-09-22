package com.transformersas.marketplace.auth.infrastructure.security;

import com.transformersas.marketplace.users.domain.model.AccountStatus;
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

/** Revalidates the JDBC session identity before CSRF, logout and endpoint authorization. */
public final class CurrentAccountSessionFilter extends OncePerRequestFilter {
    private final UserAccountRepository accounts;
    private final SecurityContextRepository contexts;

    public CurrentAccountSessionFilter(UserAccountRepository accounts, SecurityContextRepository contexts) {
        this.accounts = accounts;
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            var current = accounts.findById(principal.accountId());
            if (current.isEmpty() || current.get().status() != AccountStatus.ACTIVA) {
                var logout = new SecurityContextLogoutHandler();
                logout.setSecurityContextRepository(contexts);
                logout.logout(request, response, authentication);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            var account = current.get();
            if (!principal.roles().equals(account.roles()) || !principal.getUsername().equals(account.email())
                    || !principal.isEnabled()) {
                var refreshed = principal.refresh(account);
                // Replace instead of mutating the context shared by concurrent requests from this session.
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        refreshed, null, refreshed.getAuthorities()));
                SecurityContextHolder.setContext(context);
                contexts.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
