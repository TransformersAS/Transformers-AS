package com.transformersas.marketplace.logistics.domain.model;

import java.time.LocalDateTime;

/** Actualización logística guardada en la línea de tiempo de un pedido (append-only salvo el resultado). */
public record TrackingEvent(
        Long id,
        Long shipmentId,
        Long orderId,
        String providerEventId,
        ShipmentEventType type,
        LocalDateTime occurredAt,
        LocalDateTime receivedAt,
        TrackingSource source,
        TrackingOutcome outcome,
        String description,
        String location,
        TrackingEvidence evidence,
        String correlationId
) {
}
