package com.transformersas.marketplace.payments.infrastructure.persistence.repository;

import com.transformersas.marketplace.payments.domain.model.Refund;
import com.transformersas.marketplace.payments.domain.model.RefundStatus;
import com.transformersas.marketplace.payments.domain.repository.RefundRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/** Adaptador JDBC de reembolsos. La unicidad por causa la garantiza UNIQUE(idempotency_key). */
@Repository
public class JdbcRefundRepository implements RefundRepository {

    private static final int MAX_ERROR = 500;

    private static final String COLUMNS = """
            id, order_id, amount, idempotency_key, status, provider_reference, attempts, last_error, correlation_id,
            created_at""";

    private final JdbcClient jdbc;

    public JdbcRefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Insertion insertIfAbsent(Refund refund) {
        try {
            jdbc.sql("""
                            INSERT INTO refunds (order_id, amount, idempotency_key, status, provider_reference, attempts,
                                                 last_error, correlation_id, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(refund.orderId(), refund.amount(), refund.idempotencyKey(), refund.status().name(),
                            refund.providerReference(), refund.attempts(), refund.lastError(), refund.correlationId(),
                            refund.createdAt(), refund.createdAt())
                    .update();
            return new Insertion(findByIdempotencyKey(refund.idempotencyKey()).orElseThrow(), true);
        } catch (DuplicateKeyException duplicate) {
            return new Insertion(findByIdempotencyKey(refund.idempotencyKey()).orElseThrow(() -> duplicate), false);
        }
    }

    @Override
    public Optional<Refund> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refunds WHERE idempotency_key = ?").param(idempotencyKey)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public Optional<Ledger> lockLedger(Long orderId, String idempotencyKey) {
        Optional<BigDecimal> total = jdbc.sql("SELECT total FROM orders WHERE id = ? FOR UPDATE").param(orderId)
                .query(BigDecimal.class).optional();
        if (total.isEmpty()) {
            return Optional.empty();
        }
        // Lecturas actuales (FOR SHARE): una transacción que ya leyó antes no debe ver una foto vieja de los reembolsos.
        BigDecimal refunded = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM refunds WHERE order_id = ? "
                + "AND status <> 'FAILED' FOR SHARE").param(orderId).query(BigDecimal.class).single();
        Optional<Refund> existing = jdbc.sql("SELECT " + COLUMNS + " FROM refunds WHERE idempotency_key = ? FOR SHARE")
                .param(idempotencyKey).query((rs, row) -> map(rs)).optional();
        return Optional.of(new Ledger(total.get(), refunded, existing));
    }

    @Override
    public Optional<Refund> findById(Long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refunds WHERE id = ?").param(id)
                .query((rs, row) -> map(rs)).optional();
    }

    @Override
    public boolean markCompleted(Long id, String providerReference) {
        return jdbc.sql("""
                        UPDATE refunds SET status = 'COMPLETED', provider_reference = ?, attempts = attempts + 1,
                               last_error = NULL, updated_at = ? WHERE id = ? AND status <> 'COMPLETED'""")
                .params(providerReference, LocalDateTime.now(), id).update() == 1;
    }

    @Override
    public void markPending(Long id) {
        jdbc.sql("""
                        UPDATE refunds SET status = 'PENDING', attempts = attempts + 1, last_error = NULL, updated_at = ?
                        WHERE id = ? AND status <> 'COMPLETED'""")
                .params(LocalDateTime.now(), id).update();
    }

    @Override
    public void markFailed(Long id, String error) {
        String truncated = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR));
        jdbc.sql("""
                        UPDATE refunds SET status = 'FAILED', attempts = attempts + 1, last_error = ?, updated_at = ?
                        WHERE id = ? AND status <> 'COMPLETED'""")
                .params(truncated, LocalDateTime.now(), id).update();
    }

    private static Refund map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Refund(rs.getLong("id"), rs.getLong("order_id"), rs.getObject("amount", BigDecimal.class),
                rs.getString("idempotency_key"), RefundStatus.valueOf(rs.getString("status")),
                rs.getString("provider_reference"), rs.getInt("attempts"), rs.getString("last_error"),
                rs.getString("correlation_id"), rs.getObject("created_at", LocalDateTime.class));
    }
}
