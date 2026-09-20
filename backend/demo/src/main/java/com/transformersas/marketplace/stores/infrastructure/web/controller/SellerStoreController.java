package com.transformersas.marketplace.stores.infrastructure.web.controller;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.stores.application.dto.UpdateStoreSettingsCommand;
import com.transformersas.marketplace.stores.application.usecase.GetStoreSettingsUseCase;
import com.transformersas.marketplace.stores.application.usecase.PreviewStoreSettingsUseCase;
import com.transformersas.marketplace.stores.application.usecase.UpdateStoreSettingsUseCase;
import com.transformersas.marketplace.stores.domain.model.StorePolicy;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.infrastructure.web.request.StoreSettingsRequest;
import com.transformersas.marketplace.stores.infrastructure.web.response.StoreSettingsResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuración de la tienda del vendedor (CU-18). Adaptador HTTP delgado: la tienda y el actor salen de
 * CurrentActorProvider (nunca de la ruta ni del body) y cada endpoint delega en un único caso de uso. La consulta y
 * la vista previa se permiten siempre; solo el guardado exige que la tienda pueda modificarse (A8).
 */
@RestController
@RequestMapping("/api/seller/store")
public class SellerStoreController {

    private final CurrentActorProvider actor;
    private final GetStoreSettingsUseCase getSettings;
    private final PreviewStoreSettingsUseCase previewSettings;
    private final UpdateStoreSettingsUseCase updateSettings;

    public SellerStoreController(CurrentActorProvider actor, GetStoreSettingsUseCase getSettings,
                                 PreviewStoreSettingsUseCase previewSettings,
                                 UpdateStoreSettingsUseCase updateSettings) {
        this.actor = actor;
        this.getSettings = getSettings;
        this.previewSettings = previewSettings;
        this.updateSettings = updateSettings;
    }

    @GetMapping
    public StoreSettingsResponse get() {
        return StoreSettingsResponse.from(getSettings.execute(actor.storeId()));
    }

    @PostMapping("/preview")
    public StoreSettingsResponse preview(@RequestBody StoreSettingsRequest request) {
        return StoreSettingsResponse.from(previewSettings.execute(command(request, 0L)));
    }

    @PutMapping
    public StoreSettingsResponse update(@RequestBody StoreSettingsRequest request) {
        if (request.version() == null) {
            throw BusinessException.invalid("STORE_VERSION_REQUIRED",
                    "Falta la versión de la tienda que se está editando");
        }
        return StoreSettingsResponse.from(updateSettings.execute(command(request, request.version())));
    }

    private UpdateStoreSettingsCommand command(StoreSettingsRequest request, long expectedVersion) {
        Long storeId = actor.storeId();
        StoreProfile profile = new StoreProfile(request.name(), request.description(), request.contactEmail(),
                request.contactPhone(), request.businessHours());
        StorePolicy policy = new StorePolicy(request.returnWindowDays() == null
                ? StorePolicy.DEFAULT_RETURN_WINDOW_DAYS : request.returnWindowDays(), request.policyText());
        return new UpdateStoreSettingsCommand(storeId, actor.actorId(), expectedVersion, profile, policy);
    }
}
