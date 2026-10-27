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
                    "El reembolso de Mercado Pago requiere gestión en el panel del proveedor; no se ejecutó un reembolso simulado", null);
        }
        return legacy.refund(key, orderId, amount);
    }
}
