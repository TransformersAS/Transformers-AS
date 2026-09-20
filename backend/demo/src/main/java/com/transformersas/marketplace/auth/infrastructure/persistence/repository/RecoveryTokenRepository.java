package com.transformersas.marketplace.auth.infrastructure.persistence.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecoveryTokenRepository {
    private final JdbcTemplate jdbc;
    public RecoveryTokenRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Both issuance and consumption serialize on the same account row within the caller's transaction.
    public void lockAccount(Long accountId) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id = ? FOR UPDATE", Long.class, accountId);
    }
    public void replace(Long accountId, String hash) {
        jdbc.update("UPDATE password_recovery_tokens SET used_at = CURRENT_TIMESTAMP(6) WHERE account_id = ? AND used_at IS NULL",
                accountId);
        jdbc.update("""
                INSERT INTO password_recovery_tokens(account_id, token_hash, created_at, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6) + INTERVAL 15 MINUTE)
                """, accountId, hash);
    }
    public Optional<Long> findAccountId(String hash) {
        return jdbc.query("SELECT account_id FROM password_recovery_tokens WHERE token_hash = ?",
                (rs, row) -> rs.getLong(1), hash).stream().findFirst();
    }
    public boolean consume(String hash) {
        return jdbc.update("""
                UPDATE password_recovery_tokens SET used_at = CURRENT_TIMESTAMP(6)
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6)
                """, hash) == 1;
    }
}
