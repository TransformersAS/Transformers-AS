/** Modelos y reglas del ciclo de vida de pedidos. */
package com.transformersas.marketplace.orders.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record Order(
        Long id,
        Long accountId,
        OrderStatus status,
        BigDecimal total,
        Long addressId,
        String shippingMethod,
        String transactionId,
        LocalDateTime createdAt,
        List<OrderItem> items
) {
    public Order {
        if (id == null && accountId == null) {
            throw new IllegalArgumentException("Un pedido nuevo requiere comprador");
        }
        if (accountId != null && accountId <= 0) {
            throw new IllegalArgumentException("Identificador de comprador inválido");
        }
    }
}