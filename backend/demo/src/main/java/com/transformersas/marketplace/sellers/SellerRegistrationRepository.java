package com.transformersas.marketplace.sellers;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Acceso a la confirmación del registro y a la aceptación de condiciones (CU-12). */
@Repository
public class SellerRegistrationRepository {

    private final JdbcTemplate jdbc;

    public SellerRegistrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** ¿La cuenta ya confirmó su registro? Las cuentas anteriores a CU-12 cuentan como confirmadas. */
    public boolean isVerified(Long accountId) {
        return jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM user_accounts WHERE id = ?",
                Boolean.class, accountId);
    }

    public void markVerified(Long accountId) {
        jdbc.update("UPDATE user_accounts SET email_verified_at = COALESCE(email_verified_at, CURRENT_TIMESTAMP(6)) "
                + "WHERE id = ?", accountId);
    }

    public void recordTermsAcceptance(Long accountId, String version) {
        jdbc.update("INSERT INTO seller_terms_acceptances (account_id, terms_version, accepted_at) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE accepted_at = accepted_at", accountId, version);
    }
}
