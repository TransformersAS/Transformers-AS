package com.transformersas.marketplace.payments.domain.repository;

import com.transformersas.marketplace.payments.domain.model.RefundGatewayResult;

import java.math.BigDecimal;

/**
 * Puerto hacia la pasarela de pagos para reembolsos. Idempotente por idempotencyKey: la misma clave nunca genera un
 * segundo reembolso (RNF-043). Un adaptador real debe usar ExternalRestClients (timeout máx. 10 s, HTTPS), un Circuit
 * Breaker propio y ningún reintento automático.
 *
 * @throws com.transformersas.marketplace.payments.domain.model.RefundRequestFailedException si no se pudo procesar
 */
public interface RefundGateway {

    RefundGatewayResult refund(String idempotencyKey, Long orderId, BigDecimal amount);
}
