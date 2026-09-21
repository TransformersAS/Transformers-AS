package com.transformersas.marketplace.logistics.domain.model;

import java.time.LocalDateTime;

/** Actualización logística guardada en la línea de tiempo de una devolución. */
public record ReturnTrackingEvent(
        Long id,
        Long returnShipmentId,
        String providerEventId,
        ReturnEventType type,
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
