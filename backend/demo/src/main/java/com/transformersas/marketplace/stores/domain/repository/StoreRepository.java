/** Puertos de persistencia requeridos por tiendas. */
package com.transformersas.marketplace.stores.domain.repository;

import com.transformersas.marketplace.stores.domain.model.Store;

import java.util.Optional;

public interface StoreRepository {

    Optional<Store> findById(Long id);

    /** La tienda de la cuenta dueña; vacío si la cuenta no es dueña de ninguna. */
    Optional<Store> findByOwnerAccountId(Long accountId);

    /** Indica si otra tienda ya usa ese nombre (sin distinguir mayúsculas ni tildes). excludingStoreId puede ser nulo. */
    boolean existsByName(String name, Long excludingStoreId);

    /**
     * Guarda el perfil de una tienda existente; no toca la dueña ni el estado. Si otra tienda ganó el mismo nombre
     * lanza BusinessException CONFLICT STORE_NAME_TAKEN, si la versión ya no es la guardada, CONFLICT
     * STORE_CONCURRENT_UPDATE, y si la tienda no existe, NOT_FOUND STORE_NOT_FOUND. Devuelve la tienda con su nueva
     * versión.
     */
    Store save(Store store);

    /**
     * Hace de la cuenta la dueña de una tienda que aún no tiene. Devuelve false, sin cambios, si la tienda no existe,
     * ya tiene dueña o la cuenta ya es dueña de otra tienda.
     */
    boolean assignOwner(Long storeId, Long accountId);
}
