package com.transformersas.marketplace.orders.infrastructure.web.response;

import com.transformersas.marketplace.orders.application.dto.CancelOrderResult;

/** El pedido queda CANCELLED aunque el reembolso esté pendiente o haya fallado (refund.status lo indica). */
public record CancelOrderResponse(Long orderId, String status, String paymentStatus, Refund refund) {

    public record Refund(String status, String message) {
    }

    public static CancelOrderResponse from(CancelOrderResult result) {
        return new CancelOrderResponse(result.order().orderId(), result.order().status().name(),
                result.order().paymentStatus().name(),
                new Refund(result.refund().status().name(), result.refund().message()));
    }
}
