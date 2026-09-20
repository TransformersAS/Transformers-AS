package com.transformersas.marketplace.stores.application.dto;

import com.transformersas.marketplace.stores.domain.model.StorePolicy;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;

import java.util.Objects;

/**
 * Edición de la configuración de la tienda. Reemplaza el perfil y la política completos. expectedVersion es la
 * versión que el vendedor vio al abrir la edición: si otra edición se adelantó, se rechaza (A10).
 */
public record UpdateStoreSettingsCommand(
        Long storeId,
        Long actorId,
        long expectedVersion,
        StoreProfile profile,
        StorePolicy policy
) {
    public UpdateStoreSettingsCommand {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(policy, "policy");
    }
}
