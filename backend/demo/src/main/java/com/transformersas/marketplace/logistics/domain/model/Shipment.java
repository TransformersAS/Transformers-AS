package com.transformersas.marketplace.logistics.domain.model;

import java.time.LocalDateTime;

/** Envío registrado para un pedido. Existe a lo sumo uno por pedido. */
public record Shipment(
        Long id,
        Long orderId,
        String providerShipmentId,
        String trackingCode,
        String idempotencyKey,
        ShipmentStatus status,
        LocalDateTime createdAt
) {
}
