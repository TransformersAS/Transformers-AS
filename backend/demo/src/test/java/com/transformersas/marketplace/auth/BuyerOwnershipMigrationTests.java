package com.transformersas.marketplace.auth;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.*;

class BuyerOwnershipMigrationTests extends AbstractIntegrationTest {
    @Test
    void upgradePreservesLegacyRowsWithoutAssigningThemAndEnforcesAccountIntegrity() {
        String database = "cu08_buyer_legacy";
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + database);
        var root = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        root.execute("CREATE DATABASE " + database);
        try {
            Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                    .target("31").load().migrate();
            var legacy = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
            legacy.update("INSERT INTO carts() VALUES ()");
            legacy.update("INSERT INTO addresses(recipient_name,street,city,department,phone) VALUES ('Legacy','Calle 1','Bogotá','Bogotá','3001234567')");
            legacy.update("INSERT INTO products(name,price,stock,category,active) VALUES ('Legacy',10,5,'Hogar',TRUE)");
            legacy.update("INSERT INTO cart_items(cart_id,product_id,quantity) SELECT c.id,p.id,1 FROM carts c CROSS JOIN products p");
            legacy.update("INSERT INTO inventory_reservations(product_id,quantity,status,created_at,expires_at) SELECT id,1,'ACTIVE',NOW(),DATE_ADD(NOW(), INTERVAL 10 MINUTE) FROM products");
            Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                    .load().migrate();
            for (String table : new String[]{"carts", "addresses", "inventory_reservations"}) {
                assertThat(legacy.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(1);
                assertThat(legacy.queryForObject("SELECT account_id FROM " + table, Long.class)).isNull();
            }
            assertThat(legacy.queryForObject("SELECT quantity FROM cart_items", Integer.class)).isEqualTo(1);
            legacy.update("INSERT INTO user_accounts(email,password_hash,status) VALUES ('new@example.com',?,'ACTIVA')",
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("TestPassword!123"));
            long owner = legacy.queryForObject("SELECT id FROM user_accounts WHERE email='new@example.com'", Long.class);
            legacy.update("INSERT INTO carts(account_id) VALUES (?)", owner);
            assertThatThrownBy(() -> legacy.update("INSERT INTO carts(account_id) VALUES (?)", owner))
                    .isInstanceOf(DataIntegrityViolationException.class);
            for (String table : new String[]{"carts", "addresses", "inventory_reservations"}) {
                assertThatThrownBy(() -> legacy.update("UPDATE " + table + " SET account_id=? WHERE account_id IS NULL", Long.MAX_VALUE))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
        } finally {
            root.execute("DROP DATABASE " + database);
        }
    }
}
