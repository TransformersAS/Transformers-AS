package com.transformersas.marketplace.stores.application.dto;

import com.transformersas.marketplace.stores.domain.model.StoreStatus;

/** Lo que otros módulos necesitan saber de la operación de una tienda, sin su configuración completa. */
public record StoreOperationalStatus(Long storeId, String name, Long ownerAccountId, StoreStatus status,
                                     String statusReason) {

    public boolean canOperate() {
        return status == StoreStatus.ACTIVE;
    }
}
