package com.transformersas.marketplace.returns.application;

import com.transformersas.marketplace.auth.infrastructure.security.AccountPrincipal;
import com.transformersas.marketplace.users.domain.model.Role;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Quién solicita y consulta devoluciones como comprador (RNF-003): la cuenta autenticada con el rol activo COMPRADOR. Se
 * comprueba aquí, y no en la configuración de seguridad, para responder con un código propio y porque una cuenta con
 * varios roles solo actúa con el que tiene activo. Las rutas del vendedor usan CurrentActorProvider.
 */
@Component
public class ReturnBuyerAccess {

    /** El id de la cuenta compradora; 401 si no hay sesión y 403 si el rol activo no es COMPRADOR. */
    public Long require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new ReturnException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Se requiere autenticación");
        }
        if (principal.activeRole() != Role.COMPRADOR) {
            throw new ReturnException(HttpStatus.FORBIDDEN, "RETURN_BUYER_ROLE_REQUIRED",
                    "Para gestionar devoluciones necesitas el rol activo de comprador");
        }
        return principal.accountId();
    }
}
