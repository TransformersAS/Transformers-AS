package com.transformersas.marketplace.stores.infrastructure.web.controller;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.security.CurrentActorProvider;
import com.transformersas.marketplace.stores.application.dto.UpdateStoreSettingsCommand;
import com.transformersas.marketplace.stores.application.usecase.GetStoreSettingsUseCase;
import com.transformersas.marketplace.stores.application.usecase.PreviewStoreSettingsUseCase;
import com.transformersas.marketplace.stores.application.usecase.RemoveStoreImageUseCase;
import com.transformersas.marketplace.stores.application.usecase.UploadStoreImageUseCase;
import com.transformersas.marketplace.stores.application.usecase.UpdateStoreSettingsUseCase;
import com.transformersas.marketplace.stores.domain.model.StoreImageKind;
import com.transformersas.marketplace.stores.domain.model.StoreImageSummary;
import com.transformersas.marketplace.stores.domain.model.StorePolicy;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.infrastructure.web.request.StoreSettingsRequest;
import com.transformersas.marketplace.stores.infrastructure.web.response.StoreSettingsResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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
    private final UploadStoreImageUseCase uploadImage;
    private final RemoveStoreImageUseCase removeImage;

    public SellerStoreController(CurrentActorProvider actor, GetStoreSettingsUseCase getSettings,
                                 PreviewStoreSettingsUseCase previewSettings,
                                 UpdateStoreSettingsUseCase updateSettings, UploadStoreImageUseCase uploadImage,
                                 RemoveStoreImageUseCase removeImage) {
        this.actor = actor;
        this.getSettings = getSettings;
        this.previewSettings = previewSettings;
        this.updateSettings = updateSettings;
        this.uploadImage = uploadImage;
        this.removeImage = removeImage;
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

    /**
     * Sube o reemplaza el logo o la portada (multipart, parte "file"). El contenido se valida antes de guardar: una
     * imagen inválida se rechaza y la que ya había se conserva (A4).
     */
    @PutMapping(path = "/images/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoreSettingsResponse.ImageInfo uploadImage(@PathVariable String kind,
                                                       @RequestPart("file") MultipartFile file) throws IOException {
        StoreImageKind imageKind = StoreImageKind.fromPath(kind);
        Long storeId = actor.storeId();
        StoreImageSummary image = uploadImage.execute(storeId, actor.actorId(), imageKind, file.getBytes());
        return StoreSettingsResponse.imageInfo(storeId, image);
    }

    @DeleteMapping("/images/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeImage(@PathVariable String kind) {
        removeImage.execute(actor.storeId(), actor.actorId(), StoreImageKind.fromPath(kind));
    }

    private UpdateStoreSettingsCommand command(StoreSettingsRequest request, long expectedVersion) {
        Long storeId = actor.storeId();
        StoreProfile profile = new StoreProfile(request.name(), request.description(), request.contactEmail(),
                request.contactPhone(), request.businessHours());
        StorePolicy policy = new StorePolicy(request.returnWindowDays() == null
                ? StorePolicy.DEFAULT_RETURN_WINDOW_DAYS : request.returnWindowDays(), request.policyText());
        if (request.shippingMethods() == null) {
            throw BusinessException.invalid("STORE_SHIPPING_METHODS_REQUIRED",
                    "La tienda debe habilitar al menos un método de envío");
        }
        return new UpdateStoreSettingsCommand(storeId, actor.actorId(), expectedVersion, profile, policy,
                request.shippingMethods());
    }
}
