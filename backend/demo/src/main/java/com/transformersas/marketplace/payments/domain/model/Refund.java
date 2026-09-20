package com.transformersas.marketplace.payments.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Reembolso de un pedido. idempotencyKey identifica la causa (order-cancel-{orderId}, return-{returnId}) y es
 * única: la misma causa nunca genera dos reembolsos.
 */
public record Refund(
        Long id,
        Long orderId,
        BigDecimal amount,
        String idempotencyKey,
        RefundStatus status,
        String providerReference,
        int attempts,
        String lastError,
        String correlationId,
        LocalDateTime createdAt
) {
}
