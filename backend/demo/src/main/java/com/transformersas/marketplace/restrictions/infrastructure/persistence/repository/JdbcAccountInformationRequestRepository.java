package com.transformersas.marketplace.restrictions.infrastructure.persistence.repository;

import com.transformersas.marketplace.restrictions.domain.model.AccountInformationRequest;
import com.transformersas.marketplace.restrictions.domain.repository.AccountInformationRequestRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Solicitudes de información. Una sola abierta por cuenta: open_key vale 1 solo mientras está OPEN. */
@Repository
public class JdbcAccountInformationRequestRepository implements AccountInformationRequestRepository {

    private static final String SELECT = """
            SELECT id, account_id, message, requested_by_account_id, requested_at, due_at, status, response_text,
                   responded_at
            FROM account_information_requests""";

    private final JdbcClient jdbc;

    public JdbcAccountInformationRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Long insert(AccountInformationRequest request) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO account_information_requests (account_id, message, requested_by_account_id,
                                                                   requested_at, due_at, status)
                        VALUES (?, ?, ?, ?, ?, 'OPEN')""")
                .params(request.accountId(), request.message(), request.requestedBy(), request.requestedAt(),
                        request.dueAt())
                .update(keys);
        return keys.getKey().longValue();
    }

    @Override
    public Optional<AccountInformationRequest> findById(Long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::toRequest).optional();
    }

    @Override
    public Optional<AccountInformationRequest> findOpenByAccount(Long accountId) {
        return jdbc.sql(SELECT + " WHERE account_id = ? AND status = 'OPEN'").param(accountId)
                .query(this::toRequest).optional();
    }

    @Override
    public List<AccountInformationRequest> findByAccount(Long accountId) {
        return jdbc.sql(SELECT + " WHERE account_id = ? ORDER BY id DESC").param(accountId)
                .query(this::toRequest).list();
    }

    @Override
    public void expire(Long id) {
        jdbc.sql("UPDATE account_information_requests SET status = 'EXPIRED', open_key = NULL WHERE id = ? "
                        + "AND status = 'OPEN'")
                .param(id).update();
    }

    @Override
    public boolean answer(Long id, String response, LocalDateTime respondedAt) {
        return jdbc.sql("""
                        UPDATE account_information_requests
                        SET status = 'ANSWERED', open_key = NULL, response_text = ?, responded_at = ?
                        WHERE id = ? AND status = 'OPEN'""")
                .params(response, respondedAt, id).update() == 1;
    }

    private AccountInformationRequest toRequest(ResultSet rs, int row) throws SQLException {
        return new AccountInformationRequest(rs.getLong("id"), rs.getLong("account_id"), rs.getLong(
                "requested_by_account_id"), rs.getString("message"), rs.getObject("requested_at",
                LocalDateTime.class), rs.getObject("due_at", LocalDateTime.class),
                AccountInformationRequest.Status.valueOf(rs.getString("status")), rs.getString("response_text"),
                rs.getObject("responded_at", LocalDateTime.class));
    }
}
