package com.transformersas.marketplace.logistics.infrastructure.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.transformersas.marketplace.logistics.application.dto.ReturnTracking;
import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Seguimiento logístico de una devolución para su comprador o su tienda (RF-051, RF-110). events va en orden
 * cronológico; las de outcome OUT_OF_ORDER se conservan para trazabilidad pero no movieron el estado.
 * refresh solo aparece cuando el usuario pidió actualizar.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReturnTrackingResponse(
        Long returnId,
        String status,
        String trackingCode,
        int failedPickups,
        int maxFailedPickups,
        boolean pickupStopped,
        boolean canRequestNewPickup,
        LocalDateTime pickedUpAt,
        LocalDateTime deliveredAt,
        boolean tracking,
        boolean lastPollFailed,
        LocalDateTime lastPolledAt,
        String refresh,
        List<Event> events
) {
    public record Event(Long id, String type, LocalDateTime occurredAt, LocalDateTime receivedAt, String source,
                        String outcome, String description, String location, Evidence evidence) {
    }

    public record Evidence(String type, String reference) {
    }

    public static ReturnTrackingResponse from(ReturnTracking view) {
        ReturnShipment shipment = view.shipment();
        return new ReturnTrackingResponse(shipment.returnId(), shipment.status().name(), shipment.trackingCode(),
                shipment.failedPickups(), ReturnShipment.MAX_FAILED_PICKUPS, shipment.pickupStopped(),
                shipment.canRequestNewPickup(), shipment.pickedUpAt(), shipment.deliveredAt(), view.active(),
                view.lastPollFailed(), view.lastPolledAt(), view.refresh() == null ? null : view.refresh().name(),
                view.events().stream().map(ReturnTrackingResponse::event).toList());
    }

    private static Event event(ReturnTrackingEvent event) {
        return new Event(event.id(), event.type().name(), event.occurredAt(), event.receivedAt(),
                event.source().name(), event.outcome().name(), event.description(), event.location(),
                event.evidence() == null ? null : new Evidence(event.evidence().type(), event.evidence().reference()));
    }
}
