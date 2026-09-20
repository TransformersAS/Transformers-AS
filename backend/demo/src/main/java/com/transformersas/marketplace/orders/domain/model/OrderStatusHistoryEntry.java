package com.transformersas.marketplace.orders.domain.model;

import com.transformersas.marketplace.shared.audit.ActorType;

import java.time.LocalDateTime;

/** Un cambio de estado. fromStatus es null en el registro inicial del pedido. */
public record OrderStatusHistoryEntry(
        Long id,
        Long orderId,
        OrderStatus fromStatus,
        OrderStatus toStatus,
        ActorType actorType,
        Long actorId,
        String reason,
        String correlationId,
        LocalDateTime createdAt
) {
}
