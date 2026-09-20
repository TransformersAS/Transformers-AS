package com.transformersas.marketplace.stores.domain.model;

import java.util.Objects;

/**
 * Tienda del vendedor. ownerAccountId es la única cuenta que puede configurarla; es nulo solo en tiendas anteriores
 * a CU-18 que aún no tienen dueña. version es el número de edición guardado: una edición basada en una versión
 * anterior debe rechazarse (A10). Una tienda restringida o suspendida siempre lleva el motivo (A8).
 */
public record Store(
        Long id,
        Long ownerAccountId,
        StoreProfile profile,
        StoreStatus status,
        String statusReason,
        long version
) {

    public static final int REASON_MAX = 500;

    public Store {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(status, "status");
        if (ownerAccountId != null && ownerAccountId <= 0) {
            throw new IllegalArgumentException("Identificador de cuenta dueña inválido");
        }
        statusReason = statusReason == null || statusReason.isBlank() ? null : statusReason.strip();
        if (status != StoreStatus.ACTIVE && statusReason == null) {
            throw new IllegalArgumentException("Una tienda " + status + " requiere el motivo");
        }
        if (statusReason != null && statusReason.length() > REASON_MAX) {
            throw new IllegalArgumentException("El motivo admite hasta " + REASON_MAX + " caracteres");
        }
    }

    public boolean isOwnedBy(Long accountId) {
        return ownerAccountId != null && ownerAccountId.equals(accountId);
    }

    /** Misma tienda con otro perfil; el estado, el motivo y la versión guardada no cambian. */
    public Store withProfile(StoreProfile newProfile) {
        return new Store(id, ownerAccountId, newProfile, status, statusReason, version);
    }
}
