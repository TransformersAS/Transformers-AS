package com.transformersas.marketplace.users;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class BuyerProfileMigrationTests extends AbstractIntegrationTest {
    @Test void upgradeKeepsLegacyAccountsWithoutInventingPersonalDataOrConsent() {
        String database="cu07_legacy_profile";
        String url=MYSQL.getJdbcUrl().replace("/"+MYSQL.getDatabaseName(),"/"+database);
        var root=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()));
        root.execute("CREATE DATABASE "+database);
        try {
            Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).locations("classpath:db/migration").target("44").load().migrate();
            var legacy=new JdbcTemplate(new DriverManagerDataSource(url,"root",MYSQL.getPassword()));
            var hash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("LegacyPassword123!");
            legacy.update("INSERT INTO user_accounts(email,password_hash,status,email_verified_at) VALUES ('legacy@example.com',?,'ACTIVA',NOW())",hash);
            long id=legacy.queryForObject("SELECT id FROM user_accounts WHERE email='legacy@example.com'",Long.class);
            legacy.update("INSERT INTO user_account_roles VALUES (?,'VENDEDOR')",id);
            String originalAccount="SELECT id,email,password_hash,status,email_verified_at FROM user_accounts WHERE id=?";
            var before=legacy.queryForMap(originalAccount,id);
            Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();
            assertThat(legacy.queryForMap(originalAccount,id)).isEqualTo(before);
            assertThat(legacy.queryForObject("SELECT communications_restricted FROM user_accounts WHERE id=?",Boolean.class,id)).isFalse();
            assertThat(legacy.queryForObject("SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_accounts' AND COLUMN_NAME='communications_restricted'",String.class)).isEqualTo("0");
            assertThat(legacy.queryForList("SELECT role FROM user_account_roles WHERE account_id=?",String.class,id)).containsExactly("VENDEDOR");
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM account_profiles",Integer.class)).isZero();
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM buyer_consents",Integer.class)).isZero();
        } finally { root.execute("DROP DATABASE "+database); }
    }
}
