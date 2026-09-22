package com.transformersas.marketplace.auth.infrastructure.security;

import org.springframework.security.core.AuthenticationException;

/** Only raised after the password has been checked. */
public class EmailNotVerifiedException extends AuthenticationException {
    public EmailNotVerifiedException() { super("Debes verificar tu correo antes de iniciar sesión"); }
}
