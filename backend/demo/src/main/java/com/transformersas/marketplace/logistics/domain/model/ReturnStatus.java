package com.transformersas.marketplace.logistics.domain.model;

/**
 * Estado logístico de una devolución (RF-110). Solo cambia por información del servicio logístico (A7). Los estados
 * de CU-19 (aprobada, en inspección, reembolsada) no viven aquí: CU-25 termina en DELIVERED_TO_SELLER.
 */
public enum ReturnStatus {
    PICKUP_PENDING,
    PICKED_UP,
    IN_RETURN,
    LOGISTICS_ISSUE,
    PICKUP_FAILED,
    DELIVERED_TO_SELLER;

    public boolean isFinal() {
        return this == DELIVERED_TO_SELLER;
    }
}
