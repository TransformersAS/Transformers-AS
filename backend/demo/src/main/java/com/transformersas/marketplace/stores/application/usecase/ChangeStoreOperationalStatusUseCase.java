package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cambia el estado operativo de una tienda por una suspensión (CU-27). Corre en la transacción de quien la aplica
 * (MANDATORY), así el estado de la tienda y el registro de la suspensión se confirman o se revierten juntos.
 *
 * <p>Una tienda RESTRICTED tiene otra medida vigente que no le corresponde a la suspensión: ni suspenderla ni
 * terminar la suspensión la toca (A6).
 */
@Component
public class ChangeStoreOperationalStatusUseCase {

    private final StoreRepository stores;

    public ChangeStoreOperationalStatusUseCase(StoreRepository stores) {
        this.stores = stores;
    }

    /** Suspende una tienda activa o actualiza el motivo de una ya suspendida. Devuelve el estado resultante. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StoreStatus suspend(Long storeId, String reason) {
        Store store = find(storeId);
        if (store.status() == StoreStatus.RESTRICTED) {
            return StoreStatus.RESTRICTED;
        }
        compareAndSet(store, StoreStatus.SUSPENDED, reason);
        return StoreStatus.SUSPENDED;
    }

    /**
     * Termina la suspensión. Sin otra restricción la tienda vuelve a ACTIVE; con ella queda RESTRICTED con ese motivo
     * en vez de reactivarse (A6). Devuelve el estado resultante.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StoreStatus endSuspension(Long storeId, String otherRestriction) {
        Store store = find(storeId);
        if (store.status() != StoreStatus.SUSPENDED) {
            return store.status();
        }
        StoreStatus target = otherRestriction == null ? StoreStatus.ACTIVE : StoreStatus.RESTRICTED;
        compareAndSet(store, target, otherRestriction);
        return target;
    }

    private void compareAndSet(Store store, StoreStatus target, String reason) {
        if (!stores.changeStatus(store.id(), store.status(), target, abbreviate(reason))) {
            throw BusinessException.conflict("STORE_STATUS_CONFLICT",
                    "El estado de la tienda cambió mientras se aplicaba la medida; vuelve a intentarlo");
        }
    }

    private Store find(Long storeId) {
        return stores.findById(storeId)
                .orElseThrow(() -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe"));
    }

    private static String abbreviate(String reason) {
        if (reason == null || reason.length() <= Store.REASON_MAX) {
            return reason;
        }
        return reason.substring(0, Store.REASON_MAX - 1) + "…";
    }
}
