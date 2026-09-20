package com.transformersas.marketplace.auth.infrastructure.security;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.users.domain.model.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Identidad del vendedor a partir de la sesión autenticada (CU-08): la cuenta sale del principal y exige el rol
 * activo VENDEDOR. PROVISIONAL: todavía no existe la relación cuenta-tienda (CU-18), por lo que la tienda se toma
 * de la cabecera X-Store-Id. Solo se acepta con sesión de vendedor, pero NO se valida que la cuenta sea dueña de
 * esa tienda: cuando CU-18 la defina, este es el único punto que debe cambiar (storeId() saldrá de la cuenta).
 */
@Component
public class SessionSellerActorProvider implements CurrentActorProvider {

    static final String STORE_HEADER = "X-Store-Id";

    @Override
    public Long storeId() {
        principal();
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        String raw = attributes instanceof ServletRequestAttributes servlet
                ? servlet.getRequest().getHeader(STORE_HEADER) : null;
        if (raw == null || raw.isBlank()) {
            throw BusinessException.unauthenticated("STORE_IDENTITY_MISSING", "Falta la identidad de la tienda");
        }
        try {
            long id = Long.parseLong(raw.strip());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // Se responde igual que cuando falta la cabecera.
        }
        throw BusinessException.unauthenticated("STORE_IDENTITY_MISSING", "Falta la identidad de la tienda");
    }

    @Override
    public Long actorId() {
        return principal().accountId();
    }

    private AccountPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            throw BusinessException.unauthenticated("UNAUTHENTICATED", "Se requiere autenticación");
        }
        if (principal.activeRole() != Role.VENDEDOR) {
            throw BusinessException.forbidden("SELLER_ROLE_REQUIRED", "Se requiere el rol activo de vendedor");
        }
        return principal;
    }
}
