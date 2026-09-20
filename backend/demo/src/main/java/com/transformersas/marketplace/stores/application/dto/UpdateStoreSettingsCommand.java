package com.transformersas.marketplace.stores.application.dto;

import com.transformersas.marketplace.stores.domain.model.StorePolicy;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Edición de la configuración de la tienda. Reemplaza el perfil, la política y los métodos de envío completos.
 * expectedVersion es la versión que el vendedor vio al abrir la edición: si otra edición se adelantó, se rechaza
 * (A10). Los métodos se normalizan (sin espacios, en mayúsculas, sin repetidos y en orden alfabético).
 */
public record UpdateStoreSettingsCommand(
        Long storeId,
        Long actorId,
        long expectedVersion,
        StoreProfile profile,
        StorePolicy policy,
        List<String> shippingMethods
) {
    public UpdateStoreSettingsCommand {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(policy, "policy");
        shippingMethods = normalize(Objects.requireNonNull(shippingMethods, "shippingMethods"));
    }

    private static List<String> normalize(Collection<String> methods) {
        return methods.stream().filter(Objects::nonNull).map(method -> method.strip().toUpperCase(Locale.ROOT))
                .filter(method -> !method.isEmpty()).distinct().sorted().toList();
    }
}
