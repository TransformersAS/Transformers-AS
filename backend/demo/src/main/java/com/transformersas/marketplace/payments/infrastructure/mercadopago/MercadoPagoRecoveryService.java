package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Recuperación acotada. Lease compartido en MySQL, sin llamadas externas dentro de la transacción de reclamo. */
@Service
public class MercadoPagoRecoveryService {
    public record Claim(String id, String token, int attempt, int offset) { }
    private final JdbcTemplate jdbc;
    private final MercadoPagoClient provider;
    private final MercadoPagoCheckoutService checkouts;
    private final MercadoPagoPaymentJournal journal;
    private final TransactionTemplate transaction;
    private final int maxAttempts;
    private final int batchSize;

    public MercadoPagoRecoveryService(JdbcTemplate jdbc, MercadoPagoClient provider, MercadoPagoCheckoutService checkouts,
            MercadoPagoPaymentJournal journal, PlatformTransactionManager transactions,
            @Value("${payments.recovery.max-attempts:8}") int maxAttempts,
            @Value("${payments.recovery.batch-size:10}") int batchSize) {
        this.jdbc = jdbc; this.provider = provider; this.checkouts = checkouts; this.journal = journal;
        this.transaction = new TransactionTemplate(transactions);
        this.maxAttempts = Math.clamp(maxAttempts, 1, 20);
        this.batchSize = Math.clamp(batchSize, 1, 20);
    }

    public int recoverBatch() {
        int processed = 0;
        for (int i = 0; i < batchSize; i++) {
            Claim claim = claimNext();
            if (claim == null) break;
            recover(claim);
            processed++;
        }
        return processed;
    }

    Claim claimNext() {
        return transaction.execute(status -> {
            var rows = jdbc.queryForList("""
                    SELECT id,recovery_attempts,recovery_offset FROM payment_checkouts
                    WHERE ((status IN ('STARTING','PENDING','REJECTED') AND order_id IS NULL)
                         OR (status <> 'REVIEW' AND recovery_result IN ('MORE_RESULTS','RETRYABLE_FAILURE')))
                      AND recovery_attempts < ? AND recovery_next_at <= CURRENT_TIMESTAMP(6)
                      AND (recovery_lease_until IS NULL OR recovery_lease_until <= CURRENT_TIMESTAMP(6))
                    ORDER BY recovery_next_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, maxAttempts);
            if (rows.isEmpty()) return null;
            var row = rows.getFirst();
            String id = (String) row.get("id"), token = UUID.randomUUID().toString();
            int attempt = ((Number) row.get("recovery_attempts")).intValue() + 1;
            jdbc.update("""
                    UPDATE payment_recovery_attempts SET finished_at=CURRENT_TIMESTAMP(6),result='LEASE_EXPIRED'
                    WHERE checkout_id=? AND finished_at IS NULL
                    """, id);
            jdbc.update("""
                    UPDATE payment_checkouts SET recovery_attempts=?,recovery_lease_token=?,
                      recovery_lease_until=TIMESTAMPADD(SECOND,300,CURRENT_TIMESTAMP(6)),recovery_result='RUNNING'
                    WHERE id=?
                    """, attempt, token, id);
            jdbc.update("""
                    INSERT INTO payment_recovery_attempts(checkout_id,lease_token,attempt_number,started_at)
                    VALUES (?,?,?,CURRENT_TIMESTAMP(6))
                    """, id, token, attempt);
            return new Claim(id, token, attempt, ((Number) row.get("recovery_offset")).intValue());
        });
    }

    void recover(Claim claim) {
        String result = "WAITING_PROVIDER", error = null;
        int nextOffset = claim.offset();
        try {
            // Limita también las referencias conocidas; un checkout con demasiados pagos requiere excepción.
            var known = journal.knownPayments(claim.id());
            if (known.size() > 20) throw new IllegalStateException("TOO_MANY_PAYMENT_REFERENCES");
            for (String id : known) checkouts.recoverPayment(claim.id(), id, claim.token());
            var search = provider.searchPayments(claim.id(), claim.offset());
            long newReferences = search.ids().stream().filter(id -> !known.contains(id)).count();
            if (known.size() + newReferences > 20) throw new IllegalStateException("TOO_MANY_PAYMENT_REFERENCES");
            for (String id : search.ids()) {
                if (!known.contains(id)) checkouts.recoverPayment(claim.id(), id, claim.token());
            }
            nextOffset = search.more() ? claim.offset() + search.returned() : 0;
            String state = jdbc.queryForObject("SELECT status FROM payment_checkouts WHERE id=?", String.class, claim.id());
            result = "REVIEW".equals(state) ? "REQUIRES_REVIEW" : search.more() ? "MORE_RESULTS"
                    : "APPROVED".equals(state) ? "ORDER_LINKED" : "WAITING_PROVIDER";
        } catch (RuntimeException failure) {
            // Código controlado, sin tokens, datos del pagador ni cuerpos externos en la base.
            result = "RETRYABLE_FAILURE";
            error = failure instanceof org.springframework.web.server.ResponseStatusException response
                    ? "PROVIDER_OR_RECOVERY_HTTP_" + response.getStatusCode().value() : failure.getClass().getSimpleName();
        }
        finish(claim, result, error, nextOffset);
    }

    private void finish(Claim claim, String outcome, String error, int offset) {
        transaction.executeWithoutResult(status -> {
            int seconds = (int) Math.min(600, 30L << Math.min(claim.attempt() - 1, 5));
            String result = claim.attempt() >= maxAttempts && !"ORDER_LINKED".equals(outcome)
                    && !"REQUIRES_REVIEW".equals(outcome) ? "EXHAUSTED" : outcome;
            int changed = jdbc.update("""
                    UPDATE payment_checkouts SET recovery_lease_token=NULL,recovery_lease_until=NULL,
                      recovery_next_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),recovery_result=?,
                      recovery_last_error=?,recovery_offset=?
                    WHERE id=? AND recovery_lease_token=? AND recovery_lease_until>CURRENT_TIMESTAMP(6)
                    """, seconds, result, MercadoPagoPaymentJournal.truncate(error), offset, claim.id(), claim.token());
            if (changed == 1) jdbc.update("""
                    UPDATE payment_recovery_attempts SET finished_at=CURRENT_TIMESTAMP(6),result=?,error=?
                    WHERE lease_token=? AND finished_at IS NULL
                    """, result, MercadoPagoPaymentJournal.truncate(error), claim.token());
        });
    }
}
