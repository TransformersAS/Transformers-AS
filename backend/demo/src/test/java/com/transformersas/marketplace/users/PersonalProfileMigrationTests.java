package com.transformersas.marketplace.users;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** Comprueba actualización V45 → V46 sin perder identidad, roles, perfil ni consentimientos. */
@Testcontainers
class PersonalProfileMigrationTests {
    @Test void migracionPreservaDatosPreviosYEsRepetible() {
        try(var mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("cu09_migration").withUsername("test").withPassword("test")) {
            mysql.start();
            var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
            Flyway.configure().dataSource(ds).target("45").load().migrate();
            var jdbc=new JdbcTemplate(ds);
            jdbc.update("INSERT INTO user_accounts(id,email,password_hash,status,email_verified_at) VALUES (900,'previa@cu09.test',?,'ACTIVA',NOW())",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("Cu09MigrationTest!"));
            jdbc.update("INSERT INTO user_account_roles(account_id,role) VALUES (900,'COMPRADOR'),(900,'VENDEDOR')");
            jdbc.update("INSERT INTO account_profiles(account_id,full_name,birth_date,phone) VALUES (900,'Perfil previo','2000-01-01','3001234567')");
            jdbc.update("INSERT INTO buyer_consents VALUES (900,'v1','v1',NOW())");
            var flyway=Flyway.configure().dataSource(ds).target("46").load();assertEquals(1,flyway.migrate().migrationsExecuted);
            assertEquals("46",flyway.info().current().getVersion().toString());
            flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
            assertEquals("Perfil previo",jdbc.queryForObject("SELECT full_name FROM account_profiles WHERE account_id=900",String.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM user_account_roles WHERE account_id=900",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM buyer_consents WHERE account_id=900",Integer.class));
            jdbc.update("INSERT INTO account_profile_settings(account_id) VALUES (900)");
            assertFalse(jdbc.queryForObject("SELECT promotions FROM account_profile_settings",Boolean.class));
            assertFalse(jdbc.queryForObject("SELECT marketplace_news FROM account_profile_settings",Boolean.class));
        }
    }
}
