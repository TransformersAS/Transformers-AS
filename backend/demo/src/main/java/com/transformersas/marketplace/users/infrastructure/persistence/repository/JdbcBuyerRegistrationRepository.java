package com.transformersas.marketplace.users.infrastructure.persistence.repository;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBuyerRegistrationRepository implements com.transformersas.marketplace.users.domain.repository.BuyerRegistrationRepository {
    private final JdbcTemplate jdbc;
    public JdbcBuyerRegistrationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void saveProfile(Long id, String name, LocalDate birthDate, String phone) {
        jdbc.update("INSERT INTO account_profiles(account_id, full_name, birth_date, phone) VALUES (?,?,?,?)", id, name.strip(), birthDate, phone.strip());
    }
    public void consent(Long id, String version) {
        jdbc.update("INSERT IGNORE INTO buyer_consents(account_id,terms_version,privacy_version,accepted_at) VALUES (?,?,?,CURRENT_TIMESTAMP(6))", id, version, version);
    }
    /** Serializes concurrent enable requests. Never rewrites credentials or other roles. */
    public boolean addBuyerRole(Long id) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", Long.class, id);
        // INSERT uses the current unique index, avoiding a stale REPEATABLE_READ snapshot.
        return jdbc.update("INSERT IGNORE INTO user_account_roles(account_id,role) VALUES (?,'COMPRADOR')", id) == 1;
    }
}
