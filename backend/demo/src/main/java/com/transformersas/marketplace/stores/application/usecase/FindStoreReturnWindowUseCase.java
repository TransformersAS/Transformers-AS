package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Plazo de devolución en días que una tienda configuró (RF-060), para los módulos que lo aplican, como devoluciones
 * (CU-19). Vacío si la tienda no existe. Es siempre la política vigente: quien necesite el plazo de una solicitud
 * en curso guarda su propia copia al crearla.
 */
@Component
public class FindStoreReturnWindowUseCase {
    private final StoreRepository stores;

    public FindStoreReturnWindowUseCase(StoreRepository stores) {
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public Optional<Integer> execute(Long storeId) {
        return stores.findById(storeId).map(Store::policy).map(policy -> policy.returnWindowDays());
    }
}
