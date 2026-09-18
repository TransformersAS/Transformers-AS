package com.transformersas.marketplace.orders.domain.model;

import java.math.BigDecimal;

public record OrderItem(
        Long productId,
        String productName,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal
) {
}