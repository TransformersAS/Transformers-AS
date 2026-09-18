/** Datos de entrada y salida de los casos de uso de pedidos. */
package com.transformersas.marketplace.orders.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderConfirmation(
        Long orderId,
        String status,
        String transactionId,
        BigDecimal total,
        LocalDateTime createdAt
) {
}