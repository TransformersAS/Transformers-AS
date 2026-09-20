package com.transformersas.marketplace.payments.domain.model;

/** Respuesta de la pasarela: COMPLETED con su referencia, o PENDING si aceptó la solicitud sin confirmarla. */
public record RefundGatewayResult(RefundStatus status, String providerReference) {
}
