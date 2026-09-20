package com.transformersas.marketplace.stores.infrastructure.web.response;

import com.transformersas.marketplace.stores.application.dto.StoreSettingsView;
import com.transformersas.marketplace.stores.domain.model.Store;

import java.util.List;

/**
 * Configuración de la tienda para su dueña. status y statusReason informan por qué no puede modificarse (A8) y
 * version es la que debe devolver al guardar (A10).
 */
public record StoreSettingsResponse(
        Long id,
        String name,
        String description,
        String contactEmail,
        String contactPhone,
        String businessHours,
        int returnWindowDays,
        String policyText,
        String status,
        String statusReason,
        boolean canModify,
        long version,
        ShippingMethods shippingMethods
) {

    public record ShippingMethods(List<String> enabled, List<String> available) {
    }

    public static StoreSettingsResponse from(StoreSettingsView view) {
        Store store = view.store();
        return new StoreSettingsResponse(store.id(), store.profile().name(), store.profile().description(),
                store.profile().contactEmail(), store.profile().contactPhone(), store.profile().businessHours(),
                store.policy().returnWindowDays(), store.policy().text(), store.status().name(), store.statusReason(),
                view.canModify(), store.version(),
                new ShippingMethods(view.enabledShippingMethods(), view.availableShippingMethods()));
    }
}
