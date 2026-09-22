package com.transformersas.marketplace.auth.infrastructure.persistence.repository;

import com.transformersas.marketplace.auth.domain.repository.EmailVerificationTokenRepository;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEmailVerificationTokenRepository implements EmailVerificationTokenRepository {
    private final JdbcTemplate jdbc;

    public JdbcEmailVerificationTokenRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void lockAccount(Long accountId) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id = ? FOR UPDATE", Long.class, accountId);
    }

    @Override
    public void replace(Long accountId, String hash) {
        jdbc.update("UPDATE email_verification_tokens SET used_at = CURRENT_TIMESTAMP(6) WHERE account_id = ? AND used_at IS NULL", accountId);
        jdbc.update("""
                INSERT INTO email_verification_tokens(account_id, token_hash, created_at, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6) + INTERVAL 30 MINUTE)
                """, accountId, hash);
    }

    @Override
    public Optional<Long> findAccountId(String hash) {
        return jdbc.query("SELECT account_id FROM email_verification_tokens WHERE token_hash = ?",
                (rs, row) -> rs.getLong(1), hash).stream().findFirst();
    }

    @Override
    public boolean consume(String hash) {
        return jdbc.update("""
                UPDATE email_verification_tokens SET used_at = CURRENT_TIMESTAMP(6)
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6)
                """, hash) == 1;
    }
}
