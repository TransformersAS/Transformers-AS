package com.transformersas.marketplace.auth.application;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import org.springframework.security.core.Authentication;

/**
 * Identificador de la cuenta autenticada que usan los demás módulos para atribuir acciones (agente
 * de una decisión, reportante). Es el id numérico de la cuenta, no el correo, para no repartir datos
 * personales por auditorías y notificaciones.
 */
public final class CurrentAccount {

    private CurrentAccount() {
    }

    public static String id(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AccountPrincipal account) {
            return account.accountId().toString();
        }
        return authentication.getName();
    }
}
