package com.transformersas.marketplace.auth.infrastructure.web.controller;

import com.transformersas.marketplace.auth.application.usecase.RecoverAccountPassword;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/password-recovery")
public class PasswordRecoveryController {
    private final RecoverAccountPassword recovery;
    public PasswordRecoveryController(RecoverAccountPassword recovery) { this.recovery = recovery; }
    public record RecoveryRequest(String email) {}
    public record Confirmation(String token, String newPassword) {
        @Override public String toString() { return "Confirmation[REDACTED]"; }
    }
    public record RecoveryResponse(String message) {}

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RecoveryResponse request(@RequestBody RecoveryRequest request) {
        recovery.request(request.email());
        return new RecoveryResponse("Si la cuenta está activa, recibirás instrucciones para recuperar la contraseña");
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@RequestBody Confirmation confirmation) {
        recovery.confirm(confirmation.token(), confirmation.newPassword());
    }
}
