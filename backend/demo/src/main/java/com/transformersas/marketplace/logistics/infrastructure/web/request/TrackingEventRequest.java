package com.transformersas.marketplace.logistics.infrastructure.web.request;

import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvidence;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
import com.transformersas.marketplace.shared.error.BusinessException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Actualización que el servicio logístico envía al webhook (contrato: docs/contracts/logistics-api.md). Sirve para
 * envíos (shipmentId) y para retornos (returnId). No es estricto: el proveedor puede añadir campos nuevos sin romper
 * la integración.
 */
public record TrackingEventRequest(
        String eventId,
        String shipmentId,
        String returnId,
        String trackingCode,
        String type,
        String occurredAt,
        String description,
        String location,
        Evidence evidence
) {
    public record Evidence(String type, String reference) {
    }

    /** Vacío si el tipo es desconocido (se ignora sin error para no bloquear al proveedor con reintentos). */
    public Optional<TrackingUpdate> toShipmentUpdate() {
        Optional<ShipmentEventType> parsed = ShipmentEventType.parse(type);
        if (parsed.isEmpty() && type != null && !type.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(build(() -> new TrackingUpdate(eventId, shipmentId, trackingCode,
                parsed.orElse(null), instant(), description, location, toEvidence())));
    }

    /** Vacío si el tipo es desconocido (se ignora sin error para no bloquear al proveedor con reintentos). */
    public Optional<ReturnTrackingUpdate> toReturnUpdate() {
        Optional<ReturnEventType> parsed = ReturnEventType.parse(type);
        if (parsed.isEmpty() && type != null && !type.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(build(() -> new ReturnTrackingUpdate(eventId, returnId, trackingCode,
                parsed.orElse(null), instant(), description, location, toEvidence())));
    }

    private Instant instant() {
        try {
            return occurredAt == null ? null : Instant.parse(occurredAt);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("occurredAt debe ser una fecha ISO-8601 en UTC");
        }
    }

    private TrackingEvidence toEvidence() {
        return evidence == null ? null : new TrackingEvidence(evidence.type(), evidence.reference());
    }

    private static <T> T build(java.util.function.Supplier<T> factory) {
        try {
            return factory.get();
        } catch (IllegalArgumentException invalid) {
            throw BusinessException.invalid("INVALID_WEBHOOK_PAYLOAD", invalid.getMessage());
        }
    }
}
