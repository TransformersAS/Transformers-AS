package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tienda con la que opera un vendedor (RF-062). Con una tienda pedida, la cuenta debe ser su dueña; sin ella se usa la
 * tienda de la cuenta. Una tienda inexistente y una ajena responden igual (403 STORE_NOT_AUTHORIZED) para no revelar
 * qué ids existen.
 */
@Component
public class ResolveSellerStoreUseCase {

    private final StoreRepository stores;

    public ResolveSellerStoreUseCase(StoreRepository stores) {
        this.stores = stores;
    }

    /** requestedStoreId puede ser nulo. Devuelve el id de la tienda o lanza BusinessException 401/403. */
    @Transactional(readOnly = true)
    public Long execute(Long accountId, Long requestedStoreId) {
        if (requestedStoreId != null) {
            return stores.findById(requestedStoreId).filter(store -> store.isOwnedBy(accountId)).map(Store::id)
                    .orElseThrow(() -> BusinessException.forbidden("STORE_NOT_AUTHORIZED",
                            "La cuenta no está autorizada para operar esta tienda"));
        }
        return stores.findByOwnerAccountId(accountId).map(Store::id).orElseThrow(
                () -> BusinessException.unauthenticated("STORE_IDENTITY_MISSING", "Falta la identidad de la tienda"));
    }
}
