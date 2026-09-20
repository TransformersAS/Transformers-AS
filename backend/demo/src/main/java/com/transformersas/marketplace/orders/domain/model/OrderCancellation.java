package com.transformersas.marketplace.orders.domain.model;

import java.time.LocalDateTime;

/** Registro de la cancelación de un pedido: quién, por qué y con qué id de correlación. Uno por pedido. */
public record OrderCancellation(
        Long id,
        Long orderId,
        CancellationInitiator initiator,
        CancellationReason reason,
        String details,
        Long cancelledById,
        String correlationId,
        LocalDateTime createdAt
) {
}
