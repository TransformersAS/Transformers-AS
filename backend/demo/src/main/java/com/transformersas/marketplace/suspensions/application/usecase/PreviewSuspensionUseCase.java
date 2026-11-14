package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.dto.StoreOperationalStatus;
import com.transformersas.marketplace.stores.application.usecase.GetStoreOperationalStatusUseCase;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.suspensions.application.dto.SuspensionPreview;
import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;
import com.transformersas.marketplace.suspensions.domain.repository.StoreRecordQuery;
import com.transformersas.marketplace.suspensions.domain.repository.StoreSuspensionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Resumen de la suspensión y sus efectos antes de confirmarla (paso 12). Valida lo mismo que la aplicación, así el
 * administrador corrige motivo o duración aquí; no guarda nada.
 */
@Service
public class PreviewSuspensionUseCase {

    private final GetStoreOperationalStatusUseCase stores;
    private final StoreSuspensionRepository suspensions;
    private final StoreRecordQuery records;

    public PreviewSuspensionUseCase(GetStoreOperationalStatusUseCase stores, StoreSuspensionRepository suspensions,
                                    StoreRecordQuery records) {
        this.stores = stores;
        this.suspensions = suspensions;
        this.records = records;
    }

    @Transactional(readOnly = true)
    public SuspensionPreview execute(Long storeId, String reason, Integer durationDays) {
        StoreOperationalStatus store = stores.execute(storeId)
                .orElseThrow(() -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe"));
        StoreSuspension suspension = StoreSuspension.start(storeId, reason, durationDays, null, LocalDateTime.now());
        if (suspensions.findActiveByStore(storeId).isPresent()) {
            throw BusinessException.conflict("STORE_ALREADY_SUSPENDED",
                    "La tienda ya tiene una suspensión vigente; modifícala o finalízala");
        }
        String warning = store.status() == StoreStatus.RESTRICTED
                ? "La tienda ya tiene otra restricción vigente: " + store.statusReason()
                + ". La suspensión se registra, pero al terminar la tienda seguirá restringida." : null;
        return new SuspensionPreview(storeId, store.name(), suspension.reason(), durationDays, suspension.startsAt(),
                suspension.endsAt(), SuspensionEffects.BLOCKED, SuspensionEffects.ALLOWED,
                records.pendingOperations(storeId), warning);
    }
}
