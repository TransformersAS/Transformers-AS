package com.transformersas.marketplace.stores.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.util.Map;

/**
 * Respuesta a "¿puede modificarse esta tienda?" (A8). Cuando no puede, lleva el estado y el motivo para
 * informarlos al vendedor.
 */
public record ModificationPermission(boolean allowed, StoreStatus status, String reason) {

    public static ModificationPermission granted() {
        return new ModificationPermission(true, StoreStatus.ACTIVE, null);
    }

    public static ModificationPermission denied(StoreStatus status, String reason) {
        return new ModificationPermission(false, status, reason);
    }

    /** No hace nada si la tienda puede modificarse; si no, responde 403 con el estado y el motivo en details. */
    public void requireAllowed() {
        if (!allowed) {
            throw new BusinessException(BusinessException.Kind.FORBIDDEN, "STORE_MODIFICATION_BLOCKED",
                    "La tienda no puede modificarse porque está " + status.name().toLowerCase() + ": " + reason,
                    Map.of("status", status.name(), "reason", reason));
        }
    }
}
