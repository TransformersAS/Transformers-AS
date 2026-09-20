package com.transformersas.marketplace.logistics.application.dto;

import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingEvent;

import java.time.LocalDateTime;
import java.util.List;

/** Seguimiento de una devolución (RF-051, RF-110): estado vigente, línea de tiempo y estado de la consulta. */
public record ReturnTracking(
        ReturnShipment shipment,
        List<ReturnTrackingEvent> events,
        boolean active,
        boolean lastPollFailed,
        LocalDateTime lastPolledAt,
        RefreshOutcome refresh
) {
    public ReturnTracking withRefresh(RefreshOutcome outcome) {
        return new ReturnTracking(shipment, events, active, lastPollFailed, lastPolledAt, outcome);
    }
}
