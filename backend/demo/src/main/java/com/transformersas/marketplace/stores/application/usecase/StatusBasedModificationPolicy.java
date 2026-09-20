package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.ModificationPermission;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreModificationPolicy;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;

/**
 * Política por defecto: solo una tienda ACTIVE puede modificarse; RESTRICTED y SUSPENDED bloquean las escrituras y se
 * rechazan con su motivo. La consulta de la tienda no pasa por aquí: siempre se permite.
 */
@Component
public class StatusBasedModificationPolicy implements StoreModificationPolicy {

    private final StoreRepository stores;

    public StatusBasedModificationPolicy(StoreRepository stores) {
        this.stores = stores;
    }

    @Override
    public ModificationPermission permissionFor(Long storeId) {
        Store store = stores.findById(storeId).orElseThrow(
                () -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe"));
        return store.status() == StoreStatus.ACTIVE
                ? ModificationPermission.granted()
                : ModificationPermission.denied(store.status(), store.statusReason());
    }
}
