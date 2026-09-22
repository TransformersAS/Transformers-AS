package com.transformersas.marketplace.auth.infrastructure.security;

import com.transformersas.marketplace.users.domain.model.Role;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Buyer identity comes exclusively from the authenticated session, never request data. */
public final class BuyerAccess {
    private BuyerAccess() { }

    public static Long accountId(AccountPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Se requiere una cuenta autenticada");
        }
        if (principal.activeRole() != Role.COMPRADOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Se requiere el rol activo COMPRADOR");
        }
        return requireAccountId(principal.accountId());
    }

    public static Long requireAccountId(Long accountId) {
        if (accountId == null || accountId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Se requiere una cuenta autenticada");
        }
        return accountId;
    }
}
