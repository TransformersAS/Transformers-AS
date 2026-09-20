package com.transformersas.marketplace.stores.application.dto;

import com.transformersas.marketplace.stores.domain.model.Store;

import java.util.List;

/**
 * Configuración de la tienda tal como la ve su dueña. canModify es falso si la tienda está restringida o suspendida
 * (A8); el motivo va en store.statusReason(). enabledShippingMethods son los que la tienda ofrece y
 * availableShippingMethods los que el marketplace permite habilitar.
 */
public record StoreSettingsView(
        Store store,
        boolean canModify,
        List<String> enabledShippingMethods,
        List<String> availableShippingMethods
) {
}
