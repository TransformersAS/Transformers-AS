package com.transformersas.marketplace.logistics.domain.model;

import java.time.Instant;

/**
 * Actualización del retorno de una devolución tal como la informa el servicio logístico. returnId es la
 * referencia logística de la devolución (no el id interno de CU-19).
 */
public record ReturnTrackingUpdate(
        String eventId,
        String returnId,
        String trackingCode,
        ReturnEventType type,
        Instant occurredAt,
        String description,
        String location,
        TrackingEvidence evidence
) {
    public ReturnTrackingUpdate {
        TrackingUpdate.requireEventId(eventId);
        TrackingUpdate.requireReference(returnId, "returnId");
        TrackingUpdate.requireReference(trackingCode, "trackingCode");
        if (type == null) {
            throw new IllegalArgumentException("type es obligatorio");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt es obligatorio");
        }
        description = TrackingUpdate.truncate(description, 500);
        location = TrackingUpdate.truncate(location, 200);
        if (evidence != null) {
            evidence = new TrackingEvidence(TrackingUpdate.truncate(evidence.type(), 32),
                    TrackingUpdate.truncate(evidence.reference(), 500));
        }
    }
}
