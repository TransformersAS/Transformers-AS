package com.transformersas.marketplace.orders.domain.model;

import com.transformersas.marketplace.shared.audit.ActorType;

import java.time.LocalDateTime;

/** Novedad detectada durante la preparación. Mientras esté OPEN el pedido no puede quedar Listo para despacho. */
public record OrderIssue(
        Long id,
        Long orderId,
        IssueType type,
        String description,
        IssueStatus status,
        ActorType reportedByType,
        Long reportedById,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt,
        Long resolvedById
) {
}
