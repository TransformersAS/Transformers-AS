package com.transformersas.marketplace.orders.infrastructure.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.transformersas.marketplace.orders.application.dto.ShipmentOutcome;

/** Resultado de la solicitud de envío: CREATED con la referencia, o FAILED con el motivo. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentResponse(String status, String shipmentId, String trackingCode, String message) {

    public static ShipmentResponse from(ShipmentOutcome outcome) {
        return new ShipmentResponse(outcome.status().name(), outcome.shipmentId(), outcome.trackingCode(),
                outcome.message());
    }
}
