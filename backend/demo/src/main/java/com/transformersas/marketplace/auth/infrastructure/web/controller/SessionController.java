package com.transformersas.marketplace.auth.infrastructure.web.controller;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.auth.application.usecase.ManageAccountSessions;
import com.transformersas.marketplace.auth.application.usecase.ChangeAccountPassword;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import java.util.List;
import com.transformersas.marketplace.users.domain.model.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class SessionController {
    private final SecurityContextRepository contexts;

    private final ManageAccountSessions sessions;

    private final ChangeAccountPassword passwords;

    public SessionController(SecurityContextRepository contexts, ManageAccountSessions sessions, ChangeAccountPassword passwords) {
        this.contexts = contexts;
        this.sessions = sessions;
        this.passwords = passwords;
    }

    public record CsrfResponse(String headerName, String token) {}
    public record AuthenticatedAccountResponse(Long accountId, String email, Set<Role> roles, Role activeRole) {}
    public record ActiveRoleRequest(@NotNull Role role) {}

    public record PasswordChangeRequest(String currentPassword, String newPassword) {
        @Override
        public String toString() { return "PasswordChangeRequest[REDACTED]"; }
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AccountPrincipal principal,
                               @RequestBody PasswordChangeRequest change, HttpServletRequest request) {
        passwords.execute(principal, change.currentPassword(), change.newPassword(), request.getSession(false).getId());
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    public AuthenticatedAccountResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return new AuthenticatedAccountResponse(principal.accountId(), principal.getUsername(),
                principal.roles(), principal.activeRole());
    }

    @PutMapping("/active-role")
    public AuthenticatedAccountResponse changeRole(@AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ActiveRoleRequest selection, HttpServletRequest request, HttpServletResponse response) {
        if (!principal.roles().contains(selection.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Rol no disponible para esta cuenta");
        }
        var selected = principal.withActiveRole(selection.role());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(selected, null, selected.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return me(selected);
    }

    @GetMapping("/sessions")
    public List<ManageAccountSessions.SessionSummary> sessions(@AuthenticationPrincipal AccountPrincipal principal,
                                                              HttpServletRequest request) {
        return sessions.list(principal, request.getSession(false).getId());
    }

    @PostMapping("/sessions/revoke-others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOtherSessions(@AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest request) {
        sessions.revokeOthers(principal, request.getSession(false).getId());
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable String id,
                              HttpServletRequest request, HttpServletResponse response) {
        if (sessions.revoke(principal, id, request.getSession(false).getId())) {
            var logout = new SecurityContextLogoutHandler();
            logout.setSecurityContextRepository(contexts);
            logout.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        }
    }

    /** Minimal role-protected endpoints for validating this authentication use case. */
    @GetMapping({"/validation/comprador", "/validation/vendedor"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void validateActiveRole() {}
}
