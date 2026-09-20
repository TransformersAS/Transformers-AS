package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Asigna la cuenta dueña de una tienda que aún no tiene (por ejemplo la tienda 1, anterior a CU-18). Es el punto de
 * entrada para los demás módulos: no reemplaza a una dueña existente y una cuenta solo puede ser dueña de una tienda.
 */
@Component
public class AssignStoreOwnerUseCase {

    private final StoreRepository stores;

    public AssignStoreOwnerUseCase(StoreRepository stores) {
        this.stores = stores;
    }

    /** Devuelve true si la cuenta quedó como dueña; false si no se pudo asignar (ver StoreRepository.assignOwner). */
    @Transactional
    public boolean execute(Long storeId, Long accountId) {
        return stores.assignOwner(Objects.requireNonNull(storeId, "storeId"),
                Objects.requireNonNull(accountId, "accountId"));
    }
}
