package com.transformersas.marketplace.auth.infrastructure.web.controller;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
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

    public SessionController(SecurityContextRepository contexts) {
        this.contexts = contexts;
    }

    public record CsrfResponse(String headerName, String token) {}
    public record AuthenticatedAccountResponse(Long accountId, String email, Set<Role> roles, Role activeRole) {}
    public record ActiveRoleRequest(@NotNull Role role) {}

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

    /** Minimal role-protected endpoints for validating this authentication use case. */
    @GetMapping({"/validation/comprador", "/validation/vendedor"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void validateActiveRole() {}
}
