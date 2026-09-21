package com.transformersas.marketplace.sellers;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Acceso a los datos de verificación de correo y a la aceptación de condiciones (CU-12). */
@Repository
public class EmailVerificationRepository {

    private final JdbcTemplate jdbc;

    public EmailVerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Emitir y consumir tokens de una misma cuenta se serializan sobre su fila, dentro de la transacción del llamador. */
    public void lockAccount(Long accountId) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id = ? FOR UPDATE", Long.class, accountId);
    }

    public boolean isVerified(Long accountId) {
        return jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM user_accounts WHERE id = ?",
                Boolean.class, accountId);
    }

    public void markVerified(Long accountId) {
        jdbc.update("UPDATE user_accounts SET email_verified_at = CURRENT_TIMESTAMP(6) WHERE id = ?", accountId);
    }

    /** Anula los tokens pendientes de la cuenta y guarda el nuevo (vale 24 horas). */
    public void replaceToken(Long accountId, String hash) {
        jdbc.update("UPDATE email_verification_tokens SET used_at = CURRENT_TIMESTAMP(6) "
                + "WHERE account_id = ? AND used_at IS NULL", accountId);
        jdbc.update("""
                INSERT INTO email_verification_tokens (account_id, token_hash, created_at, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6) + INTERVAL 24 HOUR)""", accountId, hash);
    }

    public Optional<Long> findAccountId(String hash) {
        return jdbc.query("SELECT account_id FROM email_verification_tokens WHERE token_hash = ?",
                (rs, row) -> rs.getLong(1), hash).stream().findFirst();
    }

    /** Usa el token una sola vez; devuelve false si ya se usó o venció. */
    public boolean consume(String hash) {
        return jdbc.update("""
                UPDATE email_verification_tokens SET used_at = CURRENT_TIMESTAMP(6)
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6)""", hash) == 1;
    }

    public void recordTermsAcceptance(Long accountId, String version) {
        jdbc.update("INSERT INTO seller_terms_acceptances (account_id, terms_version, accepted_at) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE accepted_at = accepted_at", accountId, version);
    }
}
