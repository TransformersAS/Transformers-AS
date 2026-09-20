package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.StoreImageKind;
import com.transformersas.marketplace.stores.domain.repository.StoreModificationPolicy;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Quita el logo o la portada de la tienda. Una tienda restringida o suspendida no se modifica (A8). */
@Component
public class RemoveStoreImageUseCase {

    private final StoreRepository stores;
    private final StoreModificationPolicy modification;
    private final AuditRecorder audit;

    RemoveStoreImageUseCase(StoreRepository stores, StoreModificationPolicy modification, AuditRecorder audit) {
        this.stores = stores;
        this.modification = modification;
        this.audit = audit;
    }

    @Transactional
    public void execute(Long storeId, Long actorId, StoreImageKind kind) {
        modification.permissionFor(storeId).requireAllowed();
        if (!stores.deleteImage(storeId, kind)) {
            throw BusinessException.notFound("STORE_IMAGE_NOT_FOUND", "La tienda no tiene esa imagen");
        }
        audit.record(ActorType.SELLER, actorId, "STORE_IMAGE_REMOVED", "STORE", storeId, AuditOutcome.SUCCESS,
                Map.of("kind", kind.name()));
    }
}
