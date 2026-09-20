package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.logistics.domain.repository.ShippingMethodCatalog;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Crea la tienda de una cuenta (nombre y dueña). Nace ACTIVE, con todos los métodos de envío disponibles habilitados
 * (RF-061). Quien la invoca (el registro de vendedores) responde de que la cuenta tenga el rol VENDEDOR. Todo ocurre
 * en una transacción: si falla un paso no queda la tienda a medias.
 */
@Component
public class CreateStoreUseCase {

    private final StoreRepository stores;
    private final ShippingMethodCatalog shippingMethods;

    public CreateStoreUseCase(StoreRepository stores, ShippingMethodCatalog shippingMethods) {
        this.stores = stores;
        this.shippingMethods = shippingMethods;
    }

    @Transactional
    public Store execute(Long ownerAccountId, String name) {
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        StoreProfile profile = new StoreProfile(name, null);
        if (stores.existsByName(profile.name(), null)) {
            throw BusinessException.conflict("STORE_NAME_TAKEN", "Ya existe otra tienda con ese nombre");
        }
        if (stores.findByOwnerAccountId(ownerAccountId).isPresent()) {
            throw BusinessException.conflict("STORE_OWNER_ALREADY_HAS_STORE", "La cuenta ya es dueña de otra tienda");
        }
        Store store = stores.insert(ownerAccountId, profile);
        List<String> available = shippingMethods.availableMethods();
        stores.replaceShippingMethods(store.id(), available);
        return store;
    }
}
