package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.stores.application.dto.StoreOperationalStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** La tienda de la que una cuenta es dueña, para los módulos que actúan sobre ella por la cuenta (CU-22). */
@Component
public class FindStoreByOwnerUseCase {

    private final StoreRepository stores;

    public FindStoreByOwnerUseCase(StoreRepository stores) {
        this.stores = stores;
    }

    /** Vacío si la cuenta no es dueña de ninguna tienda. */
    @Transactional(readOnly = true)
    public Optional<StoreOperationalStatus> execute(Long ownerAccountId) {
        return stores.findByOwnerAccountId(ownerAccountId).map(store -> new StoreOperationalStatus(store.id(),
                store.profile().name(), store.ownerAccountId(), store.status(), store.statusReason()));
    }
}
