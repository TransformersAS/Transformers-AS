package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migraciones V9+ : arranque desde cero (el contexto ya corrió Flyway y Hibernate en validate), backfill de datos
 * previos sobre una base en el estado anterior, y restricciones de integridad.
 */
class SchemaMigrationTests extends AbstractIntegrationTest {

    private static final String LEGACY_DB = "legacy_backfill";

    @AfterAll
    static void dropLegacyDatabase() {
        rootJdbc("").execute("DROP DATABASE IF EXISTS " + LEGACY_DB);
    }

    private static JdbcTemplate rootJdbc(String database) {
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + database);
        return new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    @Test
    void freshDatabaseAppliedEveryMigrationAndSeededTheMainStore() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IN ('9','10','11')",
                String.class)).containsExactlyInAnyOrder("9", "10", "11");
        assertThat(jdbc.queryForMap("SELECT id, name FROM stores WHERE id = 1"))
                .containsEntry("name", "Tienda principal");
    }

    @Test
    void legacyOrdersAreBackfilledWithoutLosingOrRewritingData() {
        rootJdbc("").execute("DROP DATABASE IF EXISTS " + LEGACY_DB);
        rootJdbc("").execute("CREATE DATABASE " + LEGACY_DB);
        JdbcTemplate legacy = rootJdbc(LEGACY_DB);
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + LEGACY_DB);

        // Estado anterior a esta rama: V1-V12 (todo lo que ya estaba en main); esta rama añade V13 en adelante.
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target("12").load().migrate();
        legacy.update("""
                INSERT INTO addresses(recipient_name, street, city, department, postal_code, phone)
                VALUES ('Luis Comprador','Carrera 7 # 8-9','Cali','Valle',NULL,'3001112233')""");
        legacy.update("INSERT INTO products(name, price, stock, category, active) VALUES ('Legado',10.00,3,'Hogar',TRUE)");
        legacy.update("""
                INSERT INTO orders(status, total, address_id, shipping_method, transaction_id, created_at)
                SELECT 'CONFIRMED', 10.00, id, 'EXPRESS', 'legacy-tx', '2026-09-01 10:00:00.000000' FROM addresses""");

        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .load().migrate();

        Map<String, Object> order = legacy.queryForMap("SELECT * FROM orders WHERE transaction_id = 'legacy-tx'");
        assertThat(order).containsEntry("store_id", 1L).containsEntry("payment_status", "APPROVED")
                .containsEntry("status", "CONFIRMED").containsEntry("shipping_method", "EXPRESS")
                .containsEntry("delivery_recipient_name", "Luis Comprador")
                .containsEntry("delivery_street", "Carrera 7 # 8-9").containsEntry("delivery_city", "Cali")
                .containsEntry("delivery_department", "Valle").containsEntry("delivery_phone", "3001112233");
        assertThat(order.get("delivery_postal_code")).isNull();
        // Un pedido histórico no tiene comprador conocido: account_id (V11) queda en NULL.
        assertThat(order.get("account_id")).isNull();

        assertThat(legacy.queryForObject("SELECT store_id FROM products WHERE name = 'Legado'", Long.class)).isEqualTo(1L);
        Map<String, Object> history = legacy.queryForMap("SELECT * FROM order_status_history");
        assertThat(history).containsEntry("to_status", "CONFIRMED").containsEntry("actor_type", "SYSTEM")
                .containsEntry("correlation_id", "migration-V14");
        assertThat(history.get("from_status")).isNull();
        assertThat(legacy.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void ordersRejectUnknownStatusesAndUnknownStores() {
        long product = seedProduct(1, "P", 5, "10.00");
        long order = seedOrder(1, "CONFIRMED", product, 1, "10.00");

        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'INVENTADO' WHERE id = ?", order))
                .hasMessageContaining("chk_orders_status");
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET payment_status = 'PAGADO' WHERE id = ?", order))
                .hasMessageContaining("chk_orders_payment_status");
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET store_id = 999 WHERE id = ?", order))
                .hasMessageContaining("fk_orders_store");
        assertThatThrownBy(() -> seedProduct(999, "Huérfano", 1, "1.00")).hasMessageContaining("fk_products_store");
    }

    @Test
    void statusColumnFitsEveryLogisticsStatus() {
        long product = seedProduct(1, "P", 5, "10.00");
        long order = seedOrder(1, "CONFIRMED", product, 1, "10.00");

        jdbc.update("UPDATE orders SET status = 'DELIVERY_ATTEMPT_FAILED' WHERE id = ?", order);

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order))
                .isEqualTo("DELIVERY_ATTEMPT_FAILED");
    }

    @Test
    void historyAndAuditTablesRejectInvalidActors() {
        long product = seedProduct(1, "P", 5, "10.00");
        long order = seedOrder(1, "CONFIRMED", product, 1, "10.00");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO order_status_history(order_id, to_status, actor_type, correlation_id, created_at)
                VALUES (?, 'CONFIRMED', 'ALIEN', 'x', NOW(6))""", order)).hasMessageContaining("chk_order_status_history_actor");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO audit_events(occurred_at, actor_type, action, entity_type, entity_id, outcome, correlation_id)
                VALUES (NOW(6), 'SELLER', 'A', 'ORDER', '1', 'MAYBE', 'x')""")).hasMessageContaining("chk_audit_events_outcome");
    }
}
