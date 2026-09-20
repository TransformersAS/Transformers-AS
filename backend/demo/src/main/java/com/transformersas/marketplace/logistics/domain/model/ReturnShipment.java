package com.transformersas.marketplace.logistics.domain.model;

import java.time.LocalDateTime;

/**
 * Seguimiento logístico de una devolución aprobada por CU-19. buyerAccountId y storeId definen quién puede
 * consultarlo; failedPickups cuenta las recogidas fallidas y pickupStopped indica que ya no se piden más (A2).
 */
public record ReturnShipment(
        Long id,
        Long returnId,
        Long buyerAccountId,
        Long storeId,
        String providerReturnId,
        String trackingCode,
        ReturnStatus status,
        int failedPickups,
        boolean pickupStopped,
        LocalDateTime pickedUpAt,
        LocalDateTime deliveredAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /** Recogidas fallidas en total tras las cuales el sistema deja de pedir recogidas automáticamente. */
    public static final int MAX_FAILED_PICKUPS = 3;

    /** El comprador podría usar otra opción de recogida: falló una recogida y todavía no se llegó al tope (A1). */
    public boolean canRequestNewPickup() {
        return status == ReturnStatus.PICKUP_FAILED && !pickupStopped && failedPickups < MAX_FAILED_PICKUPS;
    }
}
