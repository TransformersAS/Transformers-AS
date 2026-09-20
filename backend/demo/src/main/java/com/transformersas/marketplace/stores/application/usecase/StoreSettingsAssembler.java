package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.logistics.domain.repository.ShippingMethodCatalog;
import com.transformersas.marketplace.stores.application.dto.StoreSettingsView;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.repository.StoreModificationPolicy;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;

/** Arma la vista de configuración de una tienda: sus datos, si puede modificarse y sus métodos de envío. */
@Component
class StoreSettingsAssembler {

    private final StoreRepository stores;
    private final StoreModificationPolicy modification;
    private final ShippingMethodCatalog shippingMethods;

    StoreSettingsAssembler(StoreRepository stores, StoreModificationPolicy modification,
                           ShippingMethodCatalog shippingMethods) {
        this.stores = stores;
        this.modification = modification;
        this.shippingMethods = shippingMethods;
    }

    StoreSettingsView assemble(Store store) {
        return new StoreSettingsView(store, modification.permissionFor(store.id()).allowed(),
                stores.findShippingMethods(store.id()), shippingMethods.availableMethods());
    }
}
