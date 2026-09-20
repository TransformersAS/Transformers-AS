package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.StoreImage;
import com.transformersas.marketplace.stores.domain.model.StoreImageKind;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Imagen pública de una tienda, para servirla. Es la única consulta que carga el contenido binario. */
@Component
public class GetStoreImageUseCase {

    private final StoreRepository stores;

    GetStoreImageUseCase(StoreRepository stores) {
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public StoreImage execute(Long storeId, StoreImageKind kind) {
        return stores.findImage(storeId, kind)
                .orElseThrow(() -> BusinessException.notFound("STORE_IMAGE_NOT_FOUND", "La tienda no tiene esa imagen"));
    }
}
