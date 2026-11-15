package com.transformersas.marketplace.restrictions.infrastructure.integration;

import com.transformersas.marketplace.restrictions.domain.port.StoreOperationalLink;
import com.transformersas.marketplace.stores.application.usecase.ChangeStoreOperationalStatusUseCase;
import com.transformersas.marketplace.stores.application.usecase.FindStoreByOwnerUseCase;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Aplica el efecto de una restricción sobre la tienda de la cuenta con los casos de uso de {@code stores}. */
@Component
class StoreOperationalLinkAdapter implements StoreOperationalLink {

    private final FindStoreByOwnerUseCase storeByOwner;
    private final ChangeStoreOperationalStatusUseCase storeStatus;

    StoreOperationalLinkAdapter(FindStoreByOwnerUseCase storeByOwner, ChangeStoreOperationalStatusUseCase storeStatus) {
        this.storeByOwner = storeByOwner;
        this.storeStatus = storeStatus;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Long> storeOwnedBy(Long accountId) {
        return storeByOwner.execute(accountId).map(store -> store.storeId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void restrict(Long storeId, String reason) {
        storeStatus.restrict(storeId, reason);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void endRestriction(Long storeId) {
        storeStatus.endRestriction(storeId);
    }
}
