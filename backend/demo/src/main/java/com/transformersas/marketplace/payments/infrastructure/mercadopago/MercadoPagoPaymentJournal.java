package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.util.List;
import static org.springframework.http.HttpStatus.*;

/** Registro mínimo durable, separado de la transacción que consume reservas y crea el pedido. */
@Repository
public class MercadoPagoPaymentJournal {
    private final JdbcTemplate jdbc;
    public MercadoPagoPaymentJournal(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String checkout, String paymentId, JsonNode payment, String lease) {
        // Mismo orden de bloqueos que la materialización: checkout antes del registro de pago.
        requireLease(checkout, lease);
        var previous = jdbc.queryForList("SELECT checkout_id FROM external_payment_observations WHERE payment_id=? FOR UPDATE", paymentId);
        if (!previous.isEmpty() && !checkout.equals(previous.getFirst().get("checkout_id")))
            throw new ResponseStatusException(CONFLICT, "El pago ya pertenece a otra compra");
        jdbc.update("""
                INSERT INTO external_payment_observations(payment_id,checkout_id,verified_status,amount,currency,
                  collector_id,approved_seen,resolution_status,first_verified_at,last_verified_at)
                VALUES (?,?,?,?,?,?,?,'PENDING_LOCAL',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE verified_status=VALUES(verified_status),verification_count=verification_count+1,
                  approved_seen=approved_seen OR VALUES(approved_seen),last_verified_at=CURRENT_TIMESTAMP(6)
                """, paymentId, checkout, payment.path("status").asString("unknown"),
                payment.path("transaction_amount").decimalValue(), payment.path("currency_id").asString(),
                payment.path("collector_id").asString(), "approved".equals(payment.path("status").asString()));
        // Compatibilidad: conserva una referencia conocida incluso si falla la materialización del pedido.
        jdbc.update("UPDATE payment_checkouts SET payment_id=COALESCE(payment_id,?),updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", paymentId, checkout);
    }

    /** Debe llamarse dentro de la transacción local que ya bloquea el checkout. */
    public void resolution(String payment, String state, Long orderId) {
        jdbc.update("UPDATE external_payment_observations SET resolution_status=?,order_id=?,last_error=NULL WHERE payment_id=?",
                state, orderId, payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void localFailure(String payment, String error) {
        jdbc.update("UPDATE external_payment_observations SET last_error=? WHERE payment_id=? AND resolution_status='PENDING_LOCAL'",
                truncate(error), payment);
    }

    public void requireLease(String checkout, String lease) {
        var rows = jdbc.queryForList("SELECT id FROM payment_checkouts WHERE id=? FOR UPDATE", checkout);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        if (lease != null && jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_checkouts WHERE id=? AND recovery_lease_token=?
                  AND recovery_lease_until>CURRENT_TIMESTAMP(6)
                """, Integer.class, checkout, lease) != 1)
            throw new ResponseStatusException(CONFLICT, "La recuperación perdió su turno de ejecución");
    }

    public List<String> knownPayments(String checkout) {
        return jdbc.queryForList("""
                SELECT payment_id FROM external_payment_observations WHERE checkout_id=?
                  AND resolution_status IN ('PENDING_LOCAL','PENDING_PAYMENT')
                UNION SELECT payment_id FROM payment_checkouts c WHERE id=? AND payment_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM external_payment_observations p WHERE p.payment_id=c.payment_id)
                """, String.class, checkout, checkout);
    }
    static String truncate(String value) { return value == null ? null : value.substring(0, Math.min(500, value.length())); }
}
