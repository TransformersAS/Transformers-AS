package com.transformersas.marketplace.logistics.application.dto;

import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Seguimiento de un pedido: referencia logística, línea de tiempo en orden cronológico y estado de la consulta.
 * lastPollFailed indica que el servicio no respondió la última vez y se muestra el último seguimiento conocido (A7).
 */
public record ShipmentTracking(
        Shipment shipment,
        List<TrackingEvent> events,
        boolean active,
        boolean lastPollFailed,
        LocalDateTime lastPolledAt
) {
}
