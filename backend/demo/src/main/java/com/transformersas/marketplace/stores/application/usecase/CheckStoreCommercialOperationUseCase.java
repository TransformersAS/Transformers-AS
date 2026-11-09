package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.Store;
import com.transformersas.marketplace.stores.domain.model.StoreStatus;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Las nuevas operaciones comerciales exigen una tienda ACTIVE (CU-27, RF-171): publicar, reactivar o editar
 * publicaciones y recibir nuevas compras. Lo que ya estaba en curso (pedidos, reclamaciones, devoluciones) no pasa por
 * aquí, así que la tienda suspendida sigue atendiéndolo (A8).
 */
@Component
public class CheckStoreCommercialOperationUseCase {

    private final StoreRepository stores;

    public CheckStoreCommercialOperationUseCase(StoreRepository stores) {
        this.stores = stores;
    }

    /** Para el vendedor: rechaza con el motivo, que es suyo, para que sepa por qué y hasta cuándo (A9). */
    @Transactional(readOnly = true)
    public void requireForSeller(Long storeId) {
        Store store = find(storeId);
        if (store.status() == StoreStatus.SUSPENDED) {
            throw BusinessException.conflict("STORE_SUSPENDED", "Tu tienda está suspendida temporalmente y no puede "
                    + "publicar, reactivar ni modificar publicaciones. " + store.statusReason());
        }
        if (store.status() == StoreStatus.RESTRICTED) {
            throw BusinessException.conflict("STORE_RESTRICTED", "Tu tienda tiene una restricción vigente y no puede "
                    + "publicar, reactivar ni modificar publicaciones. " + store.statusReason());
        }
    }

    /** Para el comprador: no revela el motivo de una medida administrativa sobre otra persona. */
    @Transactional(readOnly = true)
    public void requireForPurchase(Long storeId) {
        if (find(storeId).status() != StoreStatus.ACTIVE) {
            throw BusinessException.conflict("STORE_NOT_OPERATING",
                    "La tienda de estos productos no está recibiendo compras en este momento");
        }
    }

    /** Ids de las tiendas que no pueden vender, para ocultar sus productos del catálogo público. */
    @Transactional(readOnly = true)
    public Set<Long> nonOperatingStoreIds() {
        return stores.findNonActiveIds();
    }

    private Store find(Long storeId) {
        return stores.findById(storeId)
                .orElseThrow(() -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe"));
    }
}
