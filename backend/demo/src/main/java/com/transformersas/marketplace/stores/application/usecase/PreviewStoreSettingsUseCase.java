package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.dto.StoreSettingsView;
import com.transformersas.marketplace.stores.application.dto.UpdateStoreSettingsCommand;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vista previa antes de confirmar: aplica las mismas validaciones que el guardado y devuelve cómo quedaría la
 * configuración ya normalizada, sin guardar nada ni auditar. Se permite también con la tienda restringida: la vista
 * indica canModify=false y el motivo para que el vendedor lo sepa antes de intentar guardar.
 */
@Component
public class PreviewStoreSettingsUseCase {

    private final StoreRepository stores;
    private final StoreSettingsValidator validator;
    private final StoreSettingsAssembler assembler;

    PreviewStoreSettingsUseCase(StoreRepository stores, StoreSettingsValidator validator,
                                StoreSettingsAssembler assembler) {
        this.stores = stores;
        this.validator = validator;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public StoreSettingsView execute(UpdateStoreSettingsCommand command) {
        Store current = stores.findById(command.storeId())
                .orElseThrow(() -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe"));
        validator.validate(command);
        return assembler.assemble(current.withSettings(command.profile(), command.policy()), command.shippingMethods());
    }
}
