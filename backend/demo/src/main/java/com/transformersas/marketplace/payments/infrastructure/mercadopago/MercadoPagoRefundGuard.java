package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import com.transformersas.marketplace.payments.domain.model.RefundGatewayResult;
import com.transformersas.marketplace.payments.domain.model.RefundRequestFailedException;
import com.transformersas.marketplace.payments.domain.repository.RefundGateway;
import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

/** Never report a simulated refund as a real provider refund for a Mercado Pago order. */
@Component
@Primary
public class MercadoPagoRefundGuard implements RefundGateway {
    private final JdbcTemplate jdbc;
    private final SimulatedRefundGateway legacy;
    public MercadoPagoRefundGuard(JdbcTemplate jdbc, SimulatedRefundGateway legacy) {
        this.jdbc = jdbc; this.legacy = legacy;
    }
    @Override
    public RefundGatewayResult refund(String key, Long orderId, BigDecimal amount) {
        String transaction = jdbc.queryForObject("SELECT transaction_id FROM orders WHERE id=?", String.class, orderId);
        if (transaction != null && transaction.startsWith("MP-")) {
            throw new RefundRequestFailedException(
                    message(key, orderId), null);
        }
        return legacy.refund(key, orderId, amount);
    }
    private static String message(String key, Long orderId) {
        String context;
        if (("order-cancel-" + orderId).equals(key)) {
            context = "La devolución por cancelación requiere conciliación y verificación. "
                    + "El procesamiento automático solo se realiza si está habilitado y la operación es elegible.";
        } else if (key != null && key.startsWith("claim-")) {
            context = "El reembolso por reclamación no tiene conciliación automática implementada; requiere gestión y verificación.";
        } else if (key != null && key.startsWith("return-")) {
            context = "El reembolso por devolución de producto no tiene conciliación automática implementada; requiere gestión y verificación.";
        } else {
            context = "Esta solicitud de reembolso de Mercado Pago requiere gestión y verificación; no tiene conciliación automática garantizada.";
        }
        return context + " No se ejecutó un reembolso simulado.";
    }

}
