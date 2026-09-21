package com.transformersas.marketplace.orders.application.dto;

/**
 * Pedido cancelado y estado del reembolso. La cancelación es definitiva aunque el reembolso esté pendiente o
 * haya fallado: el reembolso queda registrado y se puede reintentar sin revertir nada.
 */
public record CancelOrderResult(OrderStatusResult order, RefundOutcome refund) {

    public record RefundOutcome(Status status, String message) {

        public enum Status { NOT_APPLICABLE, PENDING, COMPLETED, FAILED }
    }
}
