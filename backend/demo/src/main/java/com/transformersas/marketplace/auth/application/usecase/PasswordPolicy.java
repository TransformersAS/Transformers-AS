package com.transformersas.marketplace.auth.application.usecase;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static void validate(String password) {
        if (password == null || password.isBlank() || password.codePointCount(0, password.length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La nueva contraseña requiere al menos 12 caracteres y un máximo de 72 bytes UTF-8");
        }
    }
}
