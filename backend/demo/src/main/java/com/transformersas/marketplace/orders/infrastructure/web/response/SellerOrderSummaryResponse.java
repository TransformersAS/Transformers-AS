package com.transformersas.marketplace.orders.infrastructure.web.response;

import com.transformersas.marketplace.orders.domain.model.OrderSummary;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SellerOrderSummaryResponse(
        Long id,
        String status,
        String paymentStatus,
        BigDecimal total,
        String shippingMethod,
        int itemCount,
        LocalDateTime createdAt
) {
    public static SellerOrderSummaryResponse from(OrderSummary summary) {
        return new SellerOrderSummaryResponse(summary.id(), summary.status().name(), summary.paymentStatus().name(),
                summary.total(), summary.shippingMethod(), summary.itemCount(), summary.createdAt());
    }
}
