package com.transformersas.marketplace.orders.application.dto;

import com.transformersas.marketplace.orders.domain.model.CancellationInitiator;
import com.transformersas.marketplace.orders.domain.model.CancellationReason;
import com.transformersas.marketplace.shared.error.BusinessException;

/**
 * Cancelación completa de un pedido. storeId es obligatorio cuando la inicia el vendedor (acota el pedido a su
 * tienda). Cuando la inicie el comprador (CU-11), el llamador debe haber verificado antes que el pedido le pertenece.
 * details es obligatorio con el motivo OTHER.
 */
public record CancelOrderCommand(
        Long orderId,
        Long storeId,
        CancellationInitiator initiator,
        Long actorId,
        CancellationReason reason,
        String details
) {
    public CancelOrderCommand {
        if (orderId == null || initiator == null) {
            throw new IllegalArgumentException("Cancelación inválida");
        }
        if (initiator == CancellationInitiator.SELLER && storeId == null) {
            throw new IllegalArgumentException("La cancelación del vendedor requiere la tienda");
        }
        if (reason == null) {
            throw BusinessException.invalid("CANCELLATION_REASON_REQUIRED", "El motivo de la cancelación es obligatorio");
        }
        details = details == null ? null : details.strip();
        if (reason == CancellationReason.OTHER && (details == null || details.isEmpty())) {
            throw BusinessException.invalid("CANCELLATION_DETAILS_REQUIRED",
                    "Con el motivo OTHER el detalle es obligatorio");
        }
        if (details != null && details.length() > 1000) {
            throw BusinessException.invalid("CANCELLATION_DETAILS_TOO_LONG", "El detalle admite hasta 1000 caracteres");
        }
    }
}
