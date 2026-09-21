package com.transformersas.marketplace.logistics.domain.model;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Actualización de un envío tal como la informa el servicio logístico. shipmentId y trackingCode identifican el
 * envío (pasos 2 y 3); eventId identifica la actualización y hace idempotente su procesamiento (RNF-043).
 */
public record TrackingUpdate(
        String eventId,
        String shipmentId,
        String trackingCode,
        ShipmentEventType type,
        Instant occurredAt,
        String description,
        String location,
        TrackingEvidence evidence
) {
    /** Formato seguro para claves de idempotencia, notificaciones y logs. */
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public TrackingUpdate {
        requireEventId(eventId);
        requireReference(shipmentId, "shipmentId");
        requireReference(trackingCode, "trackingCode");
        if (type == null) {
            throw new IllegalArgumentException("type es obligatorio");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt es obligatorio");
        }
        description = truncate(description, 500);
        location = truncate(location, 200);
        if (evidence != null) {
            evidence = new TrackingEvidence(truncate(evidence.type(), 32), truncate(evidence.reference(), 500));
        }
    }

    static void requireEventId(String eventId) {
        if (eventId == null || !EVENT_ID.matcher(eventId).matches()) {
            throw new IllegalArgumentException("eventId es obligatorio (1 a 64 caracteres A-Z a-z 0-9 . _ : -)");
        }
    }

    static void requireReference(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException(field + " es obligatorio (hasta 100 caracteres)");
        }
    }

    static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
