package com.transformersas.marketplace.orders.infrastructure.web.response;

import com.transformersas.marketplace.orders.application.dto.ReadyForDispatchResult;

/** El pedido queda Listo para despacho aunque la solicitud de envío haya fallado (shipment.status = FAILED). */
public record ReadyForDispatchResponse(Long orderId, String status, String paymentStatus, ShipmentResponse shipment) {

    public static ReadyForDispatchResponse from(ReadyForDispatchResult result) {
        return new ReadyForDispatchResponse(result.order().orderId(), result.order().status().name(),
                result.order().paymentStatus().name(), ShipmentResponse.from(result.shipment()));
    }
}
