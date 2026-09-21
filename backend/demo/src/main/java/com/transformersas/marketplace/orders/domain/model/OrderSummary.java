package com.transformersas.marketplace.orders.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Resumen de un pedido para listados. */
public record OrderSummary(
        Long id,
        OrderStatus status,
        OrderPaymentStatus paymentStatus,
        BigDecimal total,
        String shippingMethod,
        int itemCount,
        LocalDateTime createdAt
) {
}
