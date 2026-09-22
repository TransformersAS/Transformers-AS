package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.users.domain.model.Role;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Quién puede reportar y consultar sus reportes (RF-145): la cuenta autenticada con rol activo COMPRADOR o
 * VENDEDOR. Se comprueba aquí, y no en la configuración de seguridad, para responder con un código propio y
 * porque una cuenta con varios roles solo actúa con el que tiene activo.
 */
@Component
public class ReporterAccess {

    /** Cuenta que reporta; el id es el mismo que CU-21 guarda como reportante. */
    public record Reporter(String id, Role role) {
    }

    public Reporter require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new ReportException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Se requiere autenticación");
        }
        Role role = principal.activeRole();
        if (role != Role.COMPRADOR && role != Role.VENDEDOR) {
            throw new ReportException(HttpStatus.FORBIDDEN, "REPORTER_ROLE_REQUIRED",
                    "Para reportar contenido necesitas el rol activo de comprador o vendedor");
        }
        return new Reporter(principal.accountId().toString(), role);
    }
}
