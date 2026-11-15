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
    @org.springframework.beans.factory.annotation.Value("${payments.mercadopago.collector-id:}")
    private String expectedCollector = "";
    public MercadoPagoPaymentJournal(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record SafetyCheck(long id, String verificationId, String paymentId, String phase, String result,
            java.math.BigDecimal expectedAmount, java.math.BigDecimal observedAmount,
            String expectedCurrency, String observedCurrency, String expectedCollector,
            String observedCollector, String providerStatus, java.time.Instant recordedAt) { }

    // La primera comprobación sobrevive a un rollback posterior del pedido.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String firstCheck(String checkout, String paymentId, java.math.BigDecimal total, JsonNode payment, boolean matches) {
        String verification = java.util.UUID.randomUUID().toString();
        appendCheck(verification, checkout, paymentId, "FIRST", matches ? "MATCH" : "MISMATCH", total, payment);
        return verification;
    }

    // Se confirma junto con el resultado local: un rollback no deja una falsa segunda confirmación.
    public void secondCheck(String verification, String checkout, String paymentId,
            java.math.BigDecimal total, JsonNode payment, String result) {
        appendCheck(verification, checkout, paymentId, "SECOND", result, total, payment);
    }

    private void appendCheck(String verification, String checkout, String paymentId, String phase, String result,
            java.math.BigDecimal total, JsonNode payment) {
        jdbc.update("""
                INSERT INTO payment_safety_checks(verification_id,checkout_id,payment_id,phase,result,
                  expected_amount,observed_amount,expected_currency,observed_currency,
                  expected_collector,observed_collector,provider_status)
                VALUES (?,?,?,?,?,?,?,'COP',?,?,?,?)
                """, verification, checkout, paymentId, phase, result, total,
                payment.path("transaction_amount").decimalValue(), payment.path("currency_id").asString(),
                expectedCollector, payment.path("collector_id").asString(), payment.path("status").asString("unknown"));
    }

    public List<SafetyCheck> safetyChecks(String checkout) {
        var rows = jdbc.query("SELECT * FROM payment_safety_checks WHERE checkout_id=? ORDER BY id DESC LIMIT 100",
                (r, n) -> new SafetyCheck(r.getLong("id"), r.getString("verification_id"), r.getString("payment_id"),
                        r.getString("phase"), r.getString("result"), r.getBigDecimal("expected_amount"),
                        r.getBigDecimal("observed_amount"), r.getString("expected_currency"), r.getString("observed_currency"),
                        r.getString("expected_collector"), r.getString("observed_collector"), r.getString("provider_status"),
                        r.getTimestamp("recorded_at").toInstant()), checkout);
        java.util.Collections.reverse(rows);
        return rows;
    }

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
