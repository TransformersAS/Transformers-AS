package com.transformersas.marketplace.restrictions.domain.port;

import java.util.Optional;

/**
 * Efecto de una restricción sobre la tienda de la cuenta, cuando tiene una (postcondición 5: las funciones
 * restringidas dejan de estar disponibles). Restricciones no conoce la persistencia de tiendas: solo este puerto,
 * que implementa un adaptador de {@code stores}.
 */
public interface StoreOperationalLink {

    /** La tienda de la que la cuenta es dueña, si tiene una. */
    Optional<Long> storeOwnedBy(Long accountId);

    /** Restringe la tienda (o actualiza el motivo de una ya restringida); no toca una SUSPENDED por CU-27. */
    void restrict(Long storeId, String reason);

    /** Termina la restricción de la tienda; no hace nada si no estaba RESTRICTED. */
    void endRestriction(Long storeId);
}
