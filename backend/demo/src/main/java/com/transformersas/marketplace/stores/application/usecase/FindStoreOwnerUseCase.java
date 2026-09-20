package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Cuenta dueña de una tienda, para los módulos que necesitan saber a quién pertenece un contenido (p. ej. que nadie
 * reporte lo suyo). Vacío si la tienda no existe o es anterior a CU-18 y aún no tiene dueña.
 */
@Component
public class FindStoreOwnerUseCase {
    private final StoreRepository stores;

    public FindStoreOwnerUseCase(StoreRepository stores) {
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public Optional<Long> execute(Long storeId) {
        return stores.findById(storeId).map(Store::ownerAccountId);
    }
}
