package com.transformersas.marketplace.auth.infrastructure.web.controller;

import com.transformersas.marketplace.auth.application.usecase.VerifyAccountEmail;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth/email-verification")
public class EmailVerificationController {
    private final VerifyAccountEmail verification;

    public EmailVerificationController(VerifyAccountEmail verification) { this.verification = verification; }

    public record ResendRequest(String email, String password) {
        @Override public String toString() { return "ResendRequest[REDACTED]"; }
    }
    public record Confirmation(String token) {
        @Override public String toString() { return "Confirmation[REDACTED]"; }
    }

    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resend(@RequestBody ResendRequest request) {
        if (!verification.send(request.email(), request.password())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo enviar el correo de verificación. Inténtalo de nuevo más tarde");
        }
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@RequestBody Confirmation request) { verification.confirm(request.token()); }
}
