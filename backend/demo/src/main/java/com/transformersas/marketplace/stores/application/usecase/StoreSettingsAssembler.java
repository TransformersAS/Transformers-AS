package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.logistics.domain.repository.ShippingMethodCatalog;
import com.transformersas.marketplace.stores.application.dto.StoreSettingsView;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.repository.StoreModificationPolicy;
import com.transformersas.marketplace.stores.domain.repository.StorePolicyRules;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;

import java.util.List;

/** Arma la vista de configuración de una tienda: sus datos, si puede modificarse y sus métodos de envío. */
@Component
class StoreSettingsAssembler {

    private final StoreRepository stores;
    private final StoreModificationPolicy modification;
    private final ShippingMethodCatalog shippingMethods;
    private final StorePolicyRules rules;

    StoreSettingsAssembler(StoreRepository stores, StoreModificationPolicy modification,
                           ShippingMethodCatalog shippingMethods, StorePolicyRules rules) {
        this.stores = stores;
        this.modification = modification;
        this.shippingMethods = shippingMethods;
        this.rules = rules;
    }

    StoreSettingsView assemble(Store store) {
        return assemble(store, stores.findShippingMethods(store.id()));
    }

    /** Con los métodos habilitados indicados, para la vista previa de una configuración aún no guardada. */
    StoreSettingsView assemble(Store store, List<String> enabledShippingMethods) {
        return new StoreSettingsView(store, modification.permissionFor(store.id()).allowed(),
                enabledShippingMethods, shippingMethods.availableMethods(), stores.findImageSummaries(store.id()),
                rules.minReturnWindowDays());
    }
}
