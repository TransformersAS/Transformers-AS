package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.stores.application.dto.StoreOperationalStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Estado operativo de una tienda: nombre, dueña, estado y motivo. Vacío si la tienda no existe. */
@Component
public class GetStoreOperationalStatusUseCase {

    private final StoreRepository stores;

    public GetStoreOperationalStatusUseCase(StoreRepository stores) {
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public Optional<StoreOperationalStatus> execute(Long storeId) {
        return stores.findById(storeId).map(store -> new StoreOperationalStatus(store.id(), store.profile().name(),
                store.ownerAccountId(), store.status(), store.statusReason()));
    }
}
