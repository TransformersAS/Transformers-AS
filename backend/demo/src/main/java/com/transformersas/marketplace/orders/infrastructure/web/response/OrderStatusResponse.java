package com.transformersas.marketplace.orders.infrastructure.web.response;

import com.transformersas.marketplace.orders.application.dto.OrderStatusResult;

/** Estado del pedido tras una acción. */
public record OrderStatusResponse(Long orderId, String status, String paymentStatus) {

    public static OrderStatusResponse from(OrderStatusResult result) {
        return new OrderStatusResponse(result.orderId(), result.status().name(), result.paymentStatus().name());
    }
}
