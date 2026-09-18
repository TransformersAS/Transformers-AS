package com.transformersas.marketplace.auth.infrastructure.web.controller;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.security.Principal;

@RestController
@RequestMapping("/api/auth")
public class SessionController {
    public record CsrfResponse(String headerName, String token) {}
    public record AuthenticatedAccountResponse(String email) {}

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    public AuthenticatedAccountResponse me(Principal principal) {
        return new AuthenticatedAccountResponse(principal.getName());
    }
}
