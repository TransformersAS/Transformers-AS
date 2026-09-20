package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.ModificationPermission;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreModificationPolicy;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

/**
 * Política por defecto: solo una tienda ACTIVE puede modificarse; RESTRICTED y SUSPENDED se rechazan con su motivo.
 * Todavía no es un bean de Spring: se registra cuando exista el adaptador de persistencia de StoreRepository.
 */
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
