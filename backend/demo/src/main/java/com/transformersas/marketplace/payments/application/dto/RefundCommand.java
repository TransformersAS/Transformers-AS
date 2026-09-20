package com.transformersas.marketplace.payments.application.dto;

import com.transformersas.marketplace.shared.audit.ActorType;

import java.math.BigDecimal;

/** Solicitud de reembolso. idempotencyKey: order-cancel-{orderId} (cancelación) o return-{returnId} (devolución, CU-19). */
public record RefundCommand(Long orderId, BigDecimal amount, String idempotencyKey, ActorType actorType, Long actorId) {

    public RefundCommand {
        if (orderId == null || amount == null || amount.signum() <= 0 || idempotencyKey == null
                || idempotencyKey.isBlank() || idempotencyKey.length() > 100 || actorType == null) {
            throw new IllegalArgumentException("Solicitud de reembolso inválida");
        }
    }
}
