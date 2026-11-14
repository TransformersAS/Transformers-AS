package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Devoluciones totales, una intención durable por pago. No crea pedidos ni modifica inventario. */
@Service
public class MercadoPagoFinancialService {
    public record Claim(String payment, String token) { }
    private final com.transformersas.marketplace.notifications.application.usecase.OrderNotificationPublisher notices;
    private final JdbcTemplate jdbc;
    private final MercadoPagoClient provider;
    private final MercadoPagoPaymentJournal journal;
    private final TransactionTemplate transaction;
    private final int maxAttempts;
    private final int batchSize;

    public MercadoPagoFinancialService(JdbcTemplate jdbc, MercadoPagoClient provider, MercadoPagoPaymentJournal journal,
            PlatformTransactionManager transactions, @Value("${payments.refunds.max-attempts:8}") int maxAttempts,
            @Value("${payments.refunds.batch-size:10}") int batchSize, com.transformersas.marketplace.notifications.application.usecase.OrderNotificationPublisher notices) {
        this.notices = notices; this.jdbc = jdbc; this.provider = provider; this.journal = journal;
        this.transaction = new TransactionTemplate(transactions);
        this.maxAttempts = Math.clamp(maxAttempts, 1, 20); this.batchSize = Math.clamp(batchSize, 1, 20);
    }

    /** Se recibe exclusivamente JSON consultado al proveedor y validado por el orquestador. No toma estados del navegador. */
    public void observeVerified(String paymentId, JsonNode payment) {
        if (!"refunded".equals(payment.path("status").asString())) return;
        var row = payment(paymentId);
        try { verify(row, paymentId, payment); }
        catch (IllegalStateException mismatch) {
            jdbc.update("UPDATE external_payment_observations SET last_error='FINANCIAL_IDENTITY_MISMATCH' WHERE payment_id=?", paymentId);
            return;
        }
        if (ensureIntent(paymentId))
            confirm(paymentId, null, null, "PAYMENT_STATUS");
    }

    public boolean externallyConfirmed(String paymentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM external_payment_refunds WHERE payment_id=? AND externally_confirmed=TRUE",
                Integer.class, paymentId) > 0;
    }

    public int recoverBatch() {
        discoverLegacyCancelled();
        var ids = jdbc.queryForList("""
                SELECT p.payment_id FROM external_payment_observations p
                JOIN payment_checkouts c ON c.id=p.checkout_id LEFT JOIN orders o ON o.id=c.order_id
                WHERE NOT EXISTS (SELECT 1 FROM external_payment_refunds r WHERE r.payment_id=p.payment_id)
                AND ((c.order_id IS NULL AND ((c.status='REVIEW' AND p.resolution_status='REQUIRES_REVIEW')
                        OR (c.cancelled_at IS NOT NULL AND p.resolution_status<>'PAYMENT_MISMATCH'))
                        AND (p.approved_seen=TRUE OR p.verified_status='refunded'))
                  OR p.resolution_status='ADDITIONAL_PAYMENT'
                  OR (o.status='CANCELLED' AND o.transaction_id=CONCAT('MP-',p.payment_id)))
                ORDER BY p.first_verified_at,p.payment_id LIMIT ?
                """, String.class, batchSize);
        ids.forEach(this::ensureIntent);
        int processed = 0;
        for (int i = 0; i < batchSize; i++) {
            Claim claim = claimNext();
            if (claim == null) break;
            process(claim); processed++;
        }
        return processed;
    }

    private Map<String, Object> payment(String id) {
        return jdbc.queryForMap("""
                SELECT p.*,c.order_id AS linked_order_id,c.status AS checkout_status,c.cancelled_at AS checkout_cancelled_at,c.total AS checkout_total,
                  o.status AS order_status,o.total AS order_total,o.transaction_id AS order_transaction
                FROM external_payment_observations p JOIN payment_checkouts c ON c.id=p.checkout_id
                LEFT JOIN orders o ON o.id=c.order_id WHERE p.payment_id=?
                """, id);
    }
    private Map<String, Object> lockedPayment(String id) {
        String checkout = jdbc.queryForObject("SELECT checkout_id FROM external_payment_observations WHERE payment_id=?", String.class, id);
        journal.requireLease(checkout, null);
        return jdbc.queryForMap("""
                SELECT p.*,c.order_id AS linked_order_id,c.status AS checkout_status,c.cancelled_at AS checkout_cancelled_at,c.total AS checkout_total,
                  o.status AS order_status,o.total AS order_total,o.transaction_id AS order_transaction
                FROM external_payment_observations p JOIN payment_checkouts c ON c.id=p.checkout_id
                LEFT JOIN orders o ON o.id=c.order_id WHERE p.payment_id=? FOR UPDATE
                """, id);
    }
    private void verify(Map<String, Object> row, String id, JsonNode payment) {
        if (!id.equals(payment.path("id").asString())
                || !provider.matches(payment, (String) row.get("checkout_id"), (BigDecimal) row.get("amount"))
                || ((BigDecimal) row.get("amount")).compareTo((BigDecimal) row.get("checkout_total")) != 0
                || !row.get("currency").equals(payment.path("currency_id").asString())
                || !row.get("collector_id").equals(payment.path("collector_id").asString())
                || ("refunded".equals(payment.path("status").asString()) && payment.has("transaction_amount_refunded")
                    && (!payment.path("transaction_amount_refunded").isNumber()
                        || payment.path("transaction_amount_refunded").decimalValue().compareTo((BigDecimal) row.get("amount")) != 0)))
            throw new IllegalStateException("PAYMENT_IDENTITY_MISMATCH");
    }

    private boolean ensureIntent(String id) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var row = lockedPayment(id);
            if (!jdbc.queryForList("SELECT payment_id FROM external_payment_refunds WHERE payment_id=? FOR UPDATE", id).isEmpty()) return true;
            String reason = reason(row, id);
            if (reason == null || ((BigDecimal) row.get("amount")).signum() <= 0) return false;
            Long legacy = null;
            if ("CANCELLED_ORDER".equals(reason)) {
                var refunds = jdbc.queryForList("SELECT id FROM refunds WHERE order_id=? AND idempotency_key=? FOR UPDATE",
                        row.get("linked_order_id"), "order-cancel-" + row.get("linked_order_id"));
                if (!refunds.isEmpty()) legacy = ((Number) refunds.getFirst().get("id")).longValue();
            }
            String key = UUID.nameUUIDFromBytes(("mp-full-refund-v1:" + id).getBytes(StandardCharsets.UTF_8)).toString();
            jdbc.update("INSERT INTO external_payment_refunds(payment_id,idempotency_key,amount,reason,legacy_refund_id) VALUES (?,?,?,?,?)",
                    id, key, row.get("amount"), reason, legacy);
            event(id, "INTENT", "RECORDED", null, null);
            return true;
        }));
    }
    private String reason(Map<String, Object> row, String id) {
        if ("PAYMENT_MISMATCH".equals(row.get("resolution_status"))) return null;
        if (row.get("linked_order_id") == null && (row.get("checkout_cancelled_at") != null || "REVIEW".equals(row.get("checkout_status")))) return "NO_ORDER";
        if (row.get("linked_order_id") != null && ("MP-" + id).equals(row.get("order_transaction"))
                && "CANCELLED".equals(row.get("order_status"))) return "CANCELLED_ORDER";
        if ("ADDITIONAL_PAYMENT".equals(row.get("resolution_status"))) return "ADDITIONAL_PAYMENT";
        return null;
    }

    Claim claimNext() {
        return transaction.execute(status -> {
            var rows = jdbc.queryForList("""
                    SELECT payment_id FROM external_payment_refunds WHERE status IN ('INTENT','SUBMITTING','PENDING','UNKNOWN')
                    AND externally_confirmed=FALSE AND attempts<? AND next_attempt_at<=CURRENT_TIMESTAMP(6)
                    AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6))
                    ORDER BY next_attempt_at,payment_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, maxAttempts);
            if (rows.isEmpty()) return null;
            String id = (String) rows.getFirst().get("payment_id"), token = UUID.randomUUID().toString();
            jdbc.update("UPDATE external_payment_refunds SET lease_token=?,lease_until=TIMESTAMPADD(SECOND,120,CURRENT_TIMESTAMP(6)),attempts=attempts+1 WHERE payment_id=?", token, id);
            event(id, "ATTEMPT", "STARTED", null, null);
            return new Claim(id, token);
        });
    }
    private boolean owns(Claim claim) {
        return !jdbc.queryForList("SELECT payment_id FROM external_payment_refunds WHERE payment_id=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(6) AND externally_confirmed=FALSE FOR UPDATE",
                claim.payment(), claim.token()).isEmpty();
    }

    void process(Claim claim) {
        try {
            if (!owns(claim)) return;
            var row = payment(claim.payment());
            JsonNode fresh = provider.payment(claim.payment()); verify(row, claim.payment(), fresh);
            journal.record((String) row.get("checkout_id"), claim.payment(), fresh, null);
            if ("refunded".equals(fresh.path("status").asString())) {
                confirm(claim.payment(), claim.token(), null, "PAYMENT_STATUS"); return;
            }
            if (!"approved".equals(fresh.path("status").asString())) { finish(claim, "EXCEPTION", "PAYMENT_NOT_APPROVED"); return; }
            var refunds = checkedRefunds(claim.payment(), provider.refunds(claim.payment()), (BigDecimal) row.get("amount"));
            recordQuery(claim, refunds);
            if (inspectRefunds(claim, refunds, (BigDecimal) row.get("amount"))) return;
            var intent = jdbc.queryForMap("SELECT * FROM external_payment_refunds WHERE payment_id=?", claim.payment());
            if (Boolean.TRUE.equals(intent.get("submission_started"))) {
                finish(claim, "UNKNOWN", "SUBMISSION_UNCONFIRMED"); return; // No repetir POST tras incertidumbre, ni con otra clave.
            }
            if (!authorizeSubmission(claim)) return;
            if (!owns(claim)) return;
            boolean rejected = false;
            try {
                JsonNode response = provider.requestFullRefund(claim.payment(), (String) intent.get("idempotency_key"));
                var valid = checkedRefunds(claim.payment(), List.of(response), (BigDecimal) row.get("amount")).getFirst();
                String result = valid.path("status").asString();
                response(claim, valid.path("id").asString(), result, valid.path("amount").decimalValue());
                rejected = "rejected".equals(result) || "failed".equals(result);
            } catch (RuntimeException uncertain) {
                if (uncertain instanceof org.springframework.web.server.ResponseStatusException rejection
                        && rejection.getStatusCode().value() == 422) {
                    response(claim, null, "rejected", null); rejected = true;
                } else response(claim, null, "unknown", null);
            }
            // Incluso un POST aprobado se concilia mediante una consulta, separado del commit local.
            JsonNode after = provider.payment(claim.payment()); verify(row, claim.payment(), after);
            if ("refunded".equals(after.path("status").asString())) {
                confirm(claim.payment(), claim.token(), null, "PAYMENT_STATUS"); return;
            }
            var afterRefunds = checkedRefunds(claim.payment(), provider.refunds(claim.payment()), (BigDecimal) row.get("amount"));
            recordQuery(claim, afterRefunds);
            if (!inspectRefunds(claim, afterRefunds, (BigDecimal) row.get("amount")))
                finish(claim, rejected ? "FAILED" : "UNKNOWN", rejected ? "PROVIDER_REJECTED" : "AWAITING_CONFIRMATION");
        } catch (RuntimeException failure) {
            finish(claim, "UNKNOWN", failure instanceof org.springframework.web.server.ResponseStatusException response
                    ? "PROVIDER_HTTP_" + response.getStatusCode().value() : "VERIFICATION_OR_LOCAL_FAILURE");
        }
    }

    private List<JsonNode> checkedRefunds(String payment, List<JsonNode> rows, BigDecimal amount) {
        if (rows == null || rows.size() > 100) throw new IllegalStateException("INVALID_REFUNDS_LIST");
        Set<String> ids = new HashSet<>();
        for (JsonNode row : rows) {
            if (row == null || !payment.equals(row.path("payment_id").asString()) || !row.path("id").asString().matches("[0-9]{1,30}")
                    || !ids.add(row.path("id").asString()) || !row.path("amount").isNumber()
                    || row.path("amount").decimalValue().signum() < 0 || row.path("amount").decimalValue().compareTo(amount) > 0)
                throw new IllegalStateException("REFUND_IDENTITY_MISMATCH");
        }
        return rows;
    }
    private boolean inspectRefunds(Claim claim, List<JsonNode> rows, BigDecimal amount) {
        BigDecimal confirmed = rows.stream().filter(row -> "approved".equals(row.path("status").asString()))
                .map(row -> row.path("amount").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (confirmed.compareTo(amount) > 0) { finish(claim, "EXCEPTION", "REFUND_TOTAL_MISMATCH"); return true; }
        if (confirmed.compareTo(amount) == 0) {
            confirm(claim.payment(), claim.token(), rows.size() == 1 ? rows.getFirst().path("id").asString() : null, "REFUND_QUERY"); return true;
        }
        if (!rows.isEmpty()) {
            transaction.executeWithoutResult(status -> {
                if (owns(claim)) jdbc.update("UPDATE external_payment_refunds SET submission_started=TRUE WHERE payment_id=?", claim.payment());
            });
            boolean pending = rows.stream().anyMatch(row -> !Set.of("approved", "rejected", "failed").contains(row.path("status").asString()));
            finish(claim, pending ? "PENDING" : confirmed.signum() > 0 ? "EXCEPTION" : "FAILED",
                    pending ? "EXTERNAL_REFUND_PENDING" : confirmed.signum() > 0 ? "PARTIAL_REFUND_REQUIRES_REVIEW" : "PROVIDER_REJECTED");
            return true;
        }
        return false;
    }

    private boolean authorizeSubmission(Claim claim) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var row = lockedPayment(claim.payment());
            if (row.get("linked_order_id") != null) jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR UPDATE", row.get("linked_order_id"));
            lockLegacyBeforeIntent(claim.payment());
            var intent = jdbc.queryForMap("SELECT * FROM external_payment_refunds WHERE payment_id=? FOR UPDATE", claim.payment());
            if (!owns(claim) || Boolean.TRUE.equals(intent.get("submission_started"))) return false;
            if (reason(row, claim.payment()) == null) { finish(claim, "EXCEPTION", "ELIGIBILITY_CHANGED"); return false; }
            if ("CANCELLED_ORDER".equals(intent.get("reason"))) {
                if (intent.get("legacy_refund_id") == null) { finish(claim, "EXCEPTION", "MISSING_LOCAL_REFUND"); return false; }
                var legacy = jdbc.queryForMap("SELECT * FROM refunds WHERE id=? FOR UPDATE", intent.get("legacy_refund_id"));
                if (!"FAILED".equals(legacy.get("status"))) { finish(claim, "PENDING", "WAITING_CANCELLATION_REFUND"); return false; }
                if (!withinOrderLimit(row, intent, legacy)) { finish(claim, "EXCEPTION", "ORDER_REFUND_LIMIT"); return false; }
                // Reservar el presupuesto local antes de la E/S; los fallos del guard no pueden liberarlo.
                jdbc.update("UPDATE refunds SET status='PENDING',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", legacy.get("id"));
            }
            jdbc.update("UPDATE external_payment_refunds SET submission_started=TRUE,status='SUBMITTING',updated_at=CURRENT_TIMESTAMP(6) WHERE payment_id=?", claim.payment());
            event(claim.payment(), "REQUEST_INTENT", "SUBMITTING", null, null);
            return true;
        }));
    }
    /** Mismo orden que markFailed: refund local antes de la intención externa. */
    private void lockLegacyBeforeIntent(String payment) {
        var row = jdbc.queryForMap("SELECT legacy_refund_id FROM external_payment_refunds WHERE payment_id=?", payment);
        if (row.get("legacy_refund_id") != null) jdbc.queryForList("SELECT id FROM refunds WHERE id=? FOR UPDATE", row.get("legacy_refund_id"));
    }
    private boolean withinOrderLimit(Map<String, Object> row, Map<String, Object> intent, Map<String, Object> legacy) {
        BigDecimal others = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM refunds WHERE order_id=? AND id<>? AND status<>'FAILED' FOR SHARE",
                BigDecimal.class, row.get("linked_order_id"), legacy.get("id"));
        return ((BigDecimal) intent.get("amount")).compareTo((BigDecimal) legacy.get("amount")) == 0
                && ((BigDecimal) intent.get("amount")).add(others).compareTo((BigDecimal) row.get("order_total")) <= 0;
    }

    private void recordQuery(Claim claim, List<JsonNode> rows) {
        transaction.executeWithoutResult(status -> {
            if (!owns(claim)) return;
            if (rows.isEmpty()) event(claim.payment(), "REFUND_QUERY", "EMPTY", null, null);
            for (var row : rows) event(claim.payment(), "REFUND_QUERY", row.path("status").asString(),
                    row.path("id").asString(), null, row.path("amount").decimalValue());
        });
    }
    private void response(Claim claim, String refund, String result, BigDecimal amount) {
        transaction.executeWithoutResult(status -> {
            if (!owns(claim)) return;
            jdbc.update("UPDATE external_payment_refunds SET provider_refund_id=COALESCE(?,provider_refund_id),status=?,last_error=?,updated_at=CURRENT_TIMESTAMP(6) WHERE payment_id=?",
                    refund, "unknown".equals(result) ? "UNKNOWN" : "PENDING", "unknown".equals(result) ? "SUBMISSION_UNCONFIRMED" : null, claim.payment());
            event(claim.payment(), "RESPONSE", result, refund, "unknown".equals(result) ? "SUBMISSION_UNCONFIRMED" : null, amount);
        });
    }

    private void confirm(String id, String token, String refund, String source) {
        transaction.executeWithoutResult(status -> {
            var row = lockedPayment(id);
            if (row.get("linked_order_id") != null) jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR UPDATE", row.get("linked_order_id"));
            lockLegacyBeforeIntent(id);
            var intent = jdbc.queryForMap("SELECT * FROM external_payment_refunds WHERE payment_id=? FOR UPDATE", id);
            if (Boolean.TRUE.equals(intent.get("externally_confirmed"))) return;
            if (token != null && !owns(new Claim(id, token))) return;
            String error = null;
            if ("CANCELLED_ORDER".equals(intent.get("reason"))) {
                if (intent.get("legacy_refund_id") == null) error = "MISSING_LOCAL_REFUND";
                else {
                    var legacy = jdbc.queryForMap("SELECT * FROM refunds WHERE id=? FOR UPDATE", intent.get("legacy_refund_id"));
                    if (!withinOrderLimit(row, intent, legacy)) error = "ORDER_REFUND_LIMIT";
                    else {
                        jdbc.update("UPDATE refunds SET status='COMPLETED',provider_reference=COALESCE(?,provider_reference),last_error=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", refund, legacy.get("id"));
                        jdbc.update("UPDATE orders SET payment_status='REFUNDED' WHERE id=? AND status='CANCELLED'", row.get("linked_order_id"));
                    }
                }
            }
            jdbc.update("""
                    UPDATE external_payment_refunds SET status=?,externally_confirmed=TRUE,confirmation_source=?,confirmed_at=CURRENT_TIMESTAMP(6),
                      provider_refund_id=COALESCE(?,provider_refund_id),last_error=?,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6)
                    WHERE payment_id=?
                    """, error == null ? "CONFIRMED" : "EXCEPTION", source, refund, error, id);
            journal.resolution(id, error == null ? "REFUNDED" : "FINANCIAL_EXCEPTION",
                    "CANCELLED_ORDER".equals(intent.get("reason")) ? (Long) row.get("linked_order_id") : (Long) row.get("order_id"));
            notices.financial(id, error == null ? "CONFIRMED" : "CONFIRMED_REVIEW");
            event(id, "CONFIRMATION", error == null ? "CONFIRMED" : "EXCEPTION", refund, error);
            if (error == null) {
                int unresolved = jdbc.queryForObject("SELECT COUNT(*) FROM external_payment_observations WHERE checkout_id=? AND approved_seen=TRUE AND resolution_status<>'REFUNDED'",
                        Integer.class, row.get("checkout_id"));
                if (row.get("linked_order_id") == null || "CANCELLED_ORDER".equals(intent.get("reason")))
                    jdbc.update("UPDATE payment_checkouts SET status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", unresolved == 0 ? "REFUNDED" : "REVIEW", row.get("checkout_id"));
            }
        });
    }
    private void finish(Claim claim, String state, String error) {
        transaction.executeWithoutResult(status -> {
            var rows = jdbc.queryForList("SELECT attempts FROM external_payment_refunds WHERE payment_id=? AND lease_token=? AND externally_confirmed=FALSE FOR UPDATE", claim.payment(), claim.token());
            if (rows.isEmpty()) return;
            int attempt = ((Number) rows.getFirst().get("attempts")).intValue();
            boolean retryable = Set.of("INTENT", "PENDING", "UNKNOWN").contains(state);
            String result = retryable && attempt >= maxAttempts ? "EXCEPTION" : state;
            int seconds = (int) Math.min(600, 30L << Math.min(attempt - 1, 5));
            jdbc.update("UPDATE external_payment_refunds SET status=?,last_error=?,lease_token=NULL,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) WHERE payment_id=?",
                    result, retryable && attempt >= maxAttempts ? "MAX_ATTEMPTS" : error, seconds, claim.payment());
            if (Set.of("FAILED", "EXCEPTION").contains(result)) notices.financial(claim.payment(), "REVIEW");
            event(claim.payment(), "ATTEMPT_RESULT", result, null, error);
        });
    }
    private void event(String id, String phase, String result, String refund, String error) {
        event(id, phase, result, refund, error, null);
    }
    private void event(String id, String phase, String result, String refund, String error, BigDecimal amount) {
        jdbc.update("INSERT INTO external_refund_events(payment_id,attempt_number,amount,phase,result,provider_refund_id,error) SELECT payment_id,attempts,?,?,?,?,? FROM external_payment_refunds WHERE payment_id=?",
                amount, phase, result, refund, error, id);
    }

    private void discoverLegacyCancelled() {
        // Las compras anteriores a H05-B pueden tener un payment_id conocido, sin observación verificada todavía.
        var rows = jdbc.queryForList("""
                SELECT c.id,o.transaction_id FROM payment_checkouts c JOIN orders o ON o.id=c.order_id
                WHERE o.status='CANCELLED' AND o.payment_status<>'REFUNDED' AND o.transaction_id LIKE 'MP-%'
                  AND c.financial_discovery_attempts<? AND c.financial_discovery_next_at<=CURRENT_TIMESTAMP(6)
                  AND NOT EXISTS (SELECT 1 FROM external_payment_observations p WHERE p.payment_id=SUBSTRING(o.transaction_id,4))
                LIMIT ?
                """, maxAttempts, batchSize);
        for (var row : rows) {
            String checkout = (String) row.get("id"), id = ((String) row.get("transaction_id")).substring(3);
            int claimed = jdbc.update("UPDATE payment_checkouts SET financial_discovery_attempts=financial_discovery_attempts+1,financial_discovery_next_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)) WHERE id=? AND financial_discovery_next_at<=CURRENT_TIMESTAMP(6) AND financial_discovery_attempts<?", checkout, maxAttempts);
            if (claimed == 0) continue;
            try {
                JsonNode payment = provider.payment(id);
                BigDecimal total = jdbc.queryForObject("SELECT total FROM payment_checkouts WHERE id=?", BigDecimal.class, checkout);
                if (!id.equals(payment.path("id").asString()) || !provider.matches(payment, checkout, total)) throw new IllegalStateException("IDENTITY_MISMATCH");
                journal.record(checkout, id, payment, null);
                observeVerified(id, payment);
            } catch (RuntimeException failure) {
                jdbc.update("UPDATE payment_checkouts SET financial_discovery_error='DISCOVERY_UNCONFIRMED' WHERE id=?", checkout);
            }
        }
    }
}
