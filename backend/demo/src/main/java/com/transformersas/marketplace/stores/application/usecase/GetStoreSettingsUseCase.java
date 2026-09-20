package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.dto.StoreSettingsView;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta de la configuración de la tienda (RF-058, RF-062). Siempre se permite, también con la tienda restringida
 * o suspendida: así el vendedor ve su estado y el motivo (A8). El acceso a la tienda lo resuelve quien invoca.
 */
@Component
public class GetStoreSettingsUseCase {

    private final StoreRepository stores;
    private final StoreSettingsAssembler assembler;

    GetStoreSettingsUseCase(StoreRepository stores, StoreSettingsAssembler assembler) {
        this.stores = stores;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public StoreSettingsView execute(Long storeId) {
        return assembler.assemble(stores.findById(storeId)
                .orElseThrow(() -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe")));
    }
}
