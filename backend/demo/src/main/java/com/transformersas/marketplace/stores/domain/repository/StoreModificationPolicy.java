package com.transformersas.marketplace.stores.domain.repository;

import com.transformersas.marketplace.stores.domain.model.ModificationPermission;

/**
 * Puerto "puede modificar" (A8). CU-18 lo consulta antes de cada cambio; CU-22 y CU-27 lo alimentan al suspender o
 * restringir tiendas sin tocar los casos de uso de CU-18.
 */
public interface StoreModificationPolicy {

    /** Lanza BusinessException NOT_FOUND STORE_NOT_FOUND si la tienda no existe. */
    ModificationPermission permissionFor(Long storeId);
}
