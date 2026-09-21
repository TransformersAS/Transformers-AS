package com.transformersas.marketplace.orders.application.dto;

import com.transformersas.marketplace.orders.domain.model.OrderPaymentStatus;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;

/** Estado del pedido tras una acción del vendedor. */
public record OrderStatusResult(Long orderId, OrderStatus status, OrderPaymentStatus paymentStatus) {
}
