package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.files.ImageValidator;
import com.transformersas.marketplace.shared.files.ValidatedImage;
import com.transformersas.marketplace.stores.domain.model.StoreImage;
import com.transformersas.marketplace.stores.domain.model.StoreImageKind;
import com.transformersas.marketplace.stores.domain.model.StoreImageSummary;
import com.transformersas.marketplace.stores.domain.repository.StoreModificationPolicy;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Sube o reemplaza el logo o la portada de la tienda (RF-059). Una tienda restringida o suspendida no se modifica
 * (A8). El contenido se valida por completo antes de escribir: una imagen inválida se rechaza sin tocar la que ya
 * había (A4), y el reemplazo es una sola sentencia, así que nunca queda la tienda sin imagen a medias.
 */
@Component
public class UploadStoreImageUseCase {

    private final StoreRepository stores;
    private final StoreModificationPolicy modification;
    private final ImageValidator validator;
    private final AuditRecorder audit;

    UploadStoreImageUseCase(StoreRepository stores, StoreModificationPolicy modification, ImageValidator validator,
                            AuditRecorder audit) {
        this.stores = stores;
        this.modification = modification;
        this.validator = validator;
        this.audit = audit;
    }

    @Transactional
    public StoreImageSummary execute(Long storeId, Long actorId, StoreImageKind kind, byte[] content) {
        modification.permissionFor(storeId).requireAllowed();
        ValidatedImage image = validator.validate(content);

        stores.saveImage(storeId, new StoreImage(kind, image.contentType(), image.sizeBytes(), image.sha256(), content));

        audit.record(ActorType.SELLER, actorId, "STORE_IMAGE_UPDATED", "STORE", storeId, AuditOutcome.SUCCESS,
                Map.of("kind", kind.name(), "sizeBytes", image.sizeBytes(), "sha256", image.sha256()));
        return new StoreImageSummary(kind, image.contentType(), image.sizeBytes(), image.sha256());
    }
}
