package com.transformersas.marketplace.orders.infrastructure.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.transformersas.marketplace.logistics.application.dto.ShipmentTracking;
import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvent;
import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.orders.application.dto.OrderTracking;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Seguimiento logístico de un pedido (RF-117): estado vigente y línea de tiempo en orden cronológico. events incluye
 * las actualizaciones recibidas fuera de orden (outcome OUT_OF_ORDER), que se conservan para trazabilidad pero no
 * movieron el estado. Sin envío todavía, trackingCode es nulo y events va vacío. refresh solo aparece al actualizar:
 * UNAVAILABLE significa que el servicio no respondió y lo mostrado es el último seguimiento conocido (A7).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderTrackingResponse(
        Long orderId,
        String status,
        String trackingCode,
        boolean tracking,
        boolean lastPollFailed,
        LocalDateTime lastPolledAt,
        String refresh,
        LocalDateTime deliveredAt,
        Evidence deliveryEvidence,
        List<Event> events
) {
    public record Event(Long id, String type, LocalDateTime occurredAt, LocalDateTime receivedAt, String source,
                        String outcome, String description, String location, Evidence evidence) {
    }

    public record Evidence(String type, String reference) {
    }

    public static OrderTrackingResponse from(OrderTracking view) {
        String refresh = view.refresh() == null ? null : view.refresh().name();
        ShipmentTracking shipment = view.shipment();
        if (shipment == null) {
            return new OrderTrackingResponse(view.order().id(), view.order().status().name(), null, false, false,
                    null, refresh, null, null, List.of());
        }
        // Paso 12 y 13: la fecha y la evidencia de entrega salen de la actualización que cerró el envío.
        Optional<TrackingEvent> delivery = shipment.events().stream()
                .filter(event -> event.type() == ShipmentEventType.DELIVERED
                        && event.outcome() == TrackingOutcome.APPLIED)
                .findFirst();
        return new OrderTrackingResponse(view.order().id(), view.order().status().name(),
                shipment.shipment().trackingCode(), shipment.active(), shipment.lastPollFailed(),
                shipment.lastPolledAt(), refresh, delivery.map(TrackingEvent::occurredAt).orElse(null),
                delivery.map(OrderTrackingResponse::evidence).orElse(null),
                shipment.events().stream().map(event -> new Event(event.id(), event.type().name(),
                        event.occurredAt(), event.receivedAt(), event.source().name(), event.outcome().name(),
                        event.description(), event.location(), evidence(event))).toList());
    }

    private static Evidence evidence(TrackingEvent event) {
        return event.evidence() == null ? null : new Evidence(event.evidence().type(), event.evidence().reference());
    }
}
