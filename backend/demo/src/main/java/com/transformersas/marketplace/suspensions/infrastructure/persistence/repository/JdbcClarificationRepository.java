package com.transformersas.marketplace.suspensions.infrastructure.persistence.repository;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.suspensions.domain.model.ClarificationRequest;
import com.transformersas.marketplace.suspensions.domain.repository.ClarificationRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Aclaraciones; una sola abierta por tienda (clave única sobre open_key, que solo vale 1 en OPEN). */
@Repository
public class JdbcClarificationRepository implements ClarificationRepository {

    private static final String SELECT = """
            SELECT id, store_id, requested_by_account_id, message, requested_at, due_at, status, response_text,
                   responded_at
            FROM store_clarification_requests""";

    private final JdbcClient jdbc;

    public JdbcClarificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Long insert(ClarificationRequest request) {
        var keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                            INSERT INTO store_clarification_requests (store_id, requested_by_account_id, message,
                                                                      requested_at, due_at, status)
                            VALUES (?, ?, ?, ?, ?, 'OPEN')""")
                    .params(request.storeId(), request.requestedBy(), request.message(), request.requestedAt(),
                            request.dueAt())
                    .update(keys);
        } catch (DuplicateKeyException alreadyOpen) {
            throw BusinessException.conflict("CLARIFICATION_ALREADY_OPEN",
                    "La tienda ya tiene una aclaración pendiente de respuesta");
        }
        return keys.getKey().longValue();
    }

    @Override
    public Optional<ClarificationRequest> findById(Long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::toRequest).optional();
    }

    @Override
    public Optional<ClarificationRequest> findOpenByStore(Long storeId) {
        return jdbc.sql(SELECT + " WHERE store_id = ? AND status = 'OPEN'").param(storeId)
                .query(this::toRequest).optional();
    }

    @Override
    public List<ClarificationRequest> findByStore(Long storeId) {
        return jdbc.sql(SELECT + " WHERE store_id = ? ORDER BY id DESC").param(storeId).query(this::toRequest).list();
    }

    @Override
    public boolean answer(Long id, String response, Long respondedBy, LocalDateTime respondedAt) {
        return jdbc.sql("""
                        UPDATE store_clarification_requests
                        SET status = 'ANSWERED', open_key = NULL, response_text = ?, responded_at = ?,
                            responded_by_account_id = ?
                        WHERE id = ? AND status = 'OPEN'""")
                .params(response, respondedAt, respondedBy, id).update() == 1;
    }

    @Override
    public boolean expire(Long id) {
        return jdbc.sql("""
                        UPDATE store_clarification_requests SET status = 'EXPIRED', open_key = NULL
                        WHERE id = ? AND status = 'OPEN'""")
                .param(id).update() == 1;
    }

    private ClarificationRequest toRequest(ResultSet rs, int row) throws SQLException {
        return new ClarificationRequest(rs.getLong("id"), rs.getLong("store_id"), rs.getLong("requested_by_account_id"),
                rs.getString("message"), rs.getObject("requested_at", LocalDateTime.class),
                rs.getObject("due_at", LocalDateTime.class), ClarificationRequest.Status.valueOf(rs.getString("status")),
                rs.getString("response_text"), rs.getObject("responded_at", LocalDateTime.class));
    }
}
