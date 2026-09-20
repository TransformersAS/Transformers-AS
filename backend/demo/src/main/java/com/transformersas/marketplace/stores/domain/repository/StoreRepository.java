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
     * Guarda el perfil de una tienda existente. Si otra tienda ganó el mismo nombre lanza BusinessException CONFLICT
     * STORE_NAME_TAKEN, y si la versión ya no es la guardada, CONFLICT STORE_CONCURRENT_UPDATE. Devuelve la tienda
     * con su nueva versión.
     */
    Store save(Store store);
}
