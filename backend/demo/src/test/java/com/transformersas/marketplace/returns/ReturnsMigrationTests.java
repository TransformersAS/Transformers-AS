package com.transformersas.marketplace.returns;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V28 (CU-19): las tablas de devoluciones y las restricciones que sostienen las reglas del caso de uso (una solicitud por
 * línea, estados válidos, una sola solicitud de información abierta, coherencia del origen y del problema reportado).
 */
class ReturnsMigrationTests extends AbstractIntegrationTest {
    private static final String LEGACY_DB = "legacy_returns";
    private static final List<String> RETURN_TABLES = List.of("return_evidence_files", "return_events",
            "return_information_requests", "return_requests");

    private long orderId;
    private long itemId;
    private long buyerId;

    @BeforeEach
    @AfterEach
    void cleanReturnTables() {
        RETURN_TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    @BeforeEach
    void seed() {
        buyerId = createAccount("comprador@example.com", "COMPRADOR");
        long product = seedProduct(1, "Lámpara", 5, "10.00");
        orderId = seedOrder(1, "DELIVERED", product, 2, "10.00");
        itemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", Long.class, orderId);
        jdbc.update("UPDATE orders SET account_id = ? WHERE id = ?", buyerId, orderId);
    }

    @AfterAll
    static void dropLegacyDatabase() {
        rootJdbc("").execute("DROP DATABASE IF EXISTS " + LEGACY_DB);
    }

    private static JdbcTemplate rootJdbc(String database) {
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + database);
        return new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private long insertReturn(String status, String origin, Long originClaimId) {
        jdbc.update("""
                INSERT INTO return_requests(order_id, order_item_id, product_id, product_name, quantity, unit_price,
                    refund_amount, buyer_account_id, store_id, status, reason_code, description, origin,
                    origin_claim_id, created_at, updated_at)
                SELECT order_id, id, product_id, product_name, quantity, unit_price, subtotal, ?, 1, ?, 'DEFECTIVE',
                       'No enciende', ?, ?, NOW(6), NOW(6)
                FROM order_items WHERE id = ?""", buyerId, status, origin, originClaimId, itemId);
        return jdbc.queryForObject("SELECT MAX(id) FROM return_requests", Long.class);
    }

    @Test
    void aBuyerRequestGetsItsDefaultsAndTheRefundOfTheWholeLine() {
        long id = insertReturn("REQUESTED", "BUYER", null);

        assertThat(jdbc.queryForMap("SELECT origin, problem_reported, refund_attempts, refund_amount, next_action_at "
                + "FROM return_requests WHERE id = ?", id))
                .containsEntry("origin", "BUYER").containsEntry("problem_reported", false)
                .containsEntry("refund_attempts", 0);
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM return_requests WHERE id = ?",
                java.math.BigDecimal.class, id)).isEqualByComparingTo("20.00");
    }

    @Test
    void thereIsOnlyOneRequestPerOrderLineWhateverItsState() {
        insertReturn("REJECTED", "BUYER", null);

        assertThatThrownBy(() -> insertReturn("REQUESTED", "BUYER", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyTheEightStatesOfTheUseCaseAreAccepted() {
        for (String status : List.of("REQUESTED", "IN_REVIEW", "INFO_REQUIRED", "REJECTED", "APPROVED",
                "IN_INSPECTION", "REFUND_PENDING", "FINISHED")) {
            long id = insertReturn(status, "BUYER", null);
            jdbc.update("DELETE FROM return_requests WHERE id = ?", id);
        }
        assertThatThrownBy(() -> insertReturn("CANCELLED", "BUYER", null))
                .isInstanceOf(UncategorizedSQLException.class);
    }

    @Test
    void aReturnBornFromAClaimNeedsItsClaimAndABuyerRequestCannotHaveOne() {
        assertThatThrownBy(() -> insertReturn("APPROVED", "CLAIM", null))
                .isInstanceOf(UncategorizedSQLException.class);
        assertThatThrownBy(() -> insertReturn("REQUESTED", "BUYER", 5L))
                .isInstanceOf(UncategorizedSQLException.class);
        assertThatThrownBy(() -> insertReturn("REQUESTED", "OTHER", null))
                .isInstanceOf(UncategorizedSQLException.class);

        long id = insertReturn("APPROVED", "CLAIM", 5L);
        assertThat(jdbc.queryForObject("SELECT return_window_days FROM return_requests WHERE id = ?", Integer.class,
                id)).isNull();
    }

    @Test
    void theStoreReturnWindowCopyCannotBeBelowThirtyDays() {
        long id = insertReturn("REQUESTED", "BUYER", null);

        jdbc.update("UPDATE return_requests SET return_window_days = 30 WHERE id = ?", id);
        assertThatThrownBy(() -> jdbc.update("UPDATE return_requests SET return_window_days = 29 WHERE id = ?", id))
                .isInstanceOf(UncategorizedSQLException.class);
    }

    @Test
    void aReportedProblemNeedsItsDescriptionAndTheClaimOnlyExistsWithAProblem() {
        long id = insertReturn("IN_INSPECTION", "BUYER", null);

        assertThatThrownBy(() -> jdbc.update("UPDATE return_requests SET claim_id = 9 WHERE id = ?", id))
                .isInstanceOf(UncategorizedSQLException.class);
        jdbc.update("""
                UPDATE return_requests SET problem_reported = TRUE, problem_description = 'Llegó roto',
                    problem_reported_at = NOW(6), claim_id = 9 WHERE id = ?""", id);
        assertThat(jdbc.queryForObject("SELECT claim_id FROM return_requests WHERE id = ?", Long.class, id))
                .isEqualTo(9L);
    }

    @Test
    void onlyOneInformationRequestCanBeOpenAndAnsweringItFreesTheSlot() {
        long id = insertReturn("INFO_REQUIRED", "BUYER", null);
        String open = """
                INSERT INTO return_information_requests(return_id, message, requested_by_account_id, requested_at, due_at,
                    status) VALUES (?, 'Envíe una foto', 1, NOW(6), DATE_ADD(NOW(6), INTERVAL 24 HOUR), 'OPEN')""";
        jdbc.update(open, id);

        assertThatThrownBy(() -> jdbc.update(open, id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                UPDATE return_information_requests SET status = 'ANSWERED', open_key = NULL,
                    response_text = 'Adjunta', responded_at = NOW(6) WHERE return_id = ?""", id);
        jdbc.update(open, id);
        assertThat(count("return_information_requests")).isEqualTo(2);
    }

    @Test
    void anAnsweredRequestCannotKeepItsOpenKeyAndAnOpenOneCannotLoseIt() {
        long id = insertReturn("INFO_REQUIRED", "BUYER", null);
        jdbc.update("""
                INSERT INTO return_information_requests(return_id, message, requested_by_account_id, requested_at, due_at,
                    status) VALUES (?, 'Envíe una foto', 1, NOW(6), DATE_ADD(NOW(6), INTERVAL 24 HOUR), 'OPEN')""", id);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE return_information_requests SET status = 'ANSWERED' WHERE return_id = ?", id))
                .isInstanceOf(UncategorizedSQLException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE return_information_requests SET open_key = NULL WHERE return_id = ?", id))
                .isInstanceOf(UncategorizedSQLException.class);
    }

    @Test
    void theTimelineOnlyGrowsInOrderPerReturn() {
        long id = insertReturn("REQUESTED", "BUYER", null);
        for (String type : List.of("REQUESTED", "REVIEW_STARTED", "INFORMATION_REQUESTED")) {
            jdbc.update("""
                    INSERT INTO return_events(return_id, event_type, actor_type, actor_id, correlation_id, created_at)
                    VALUES (?, ?, 'BUYER', ?, 'test', NOW(6))""", id, type, buyerId);
        }

        assertThat(jdbc.queryForList("SELECT event_type FROM return_events WHERE return_id = ? ORDER BY id",
                String.class, id)).containsExactly("REQUESTED", "REVIEW_STARTED", "INFORMATION_REQUESTED");
    }

    @Test
    void evidenceImagesAreNumberedPerReturnAndGoAwayWithIt() {
        long id = insertReturn("REQUESTED", "BUYER", null);
        String insert = """
                INSERT INTO return_evidence_files(return_id, ordinal, file_name, content_type, size_bytes, sha256, data,
                    created_at) VALUES (?, ?, 'a.png', 'image/png', 3, REPEAT('a', 64), ?, NOW(6))""";
        jdbc.update(insert, id, 1, new byte[]{1, 2, 3});
        jdbc.update(insert, id, 2, new byte[]{1, 2, 3});

        assertThatThrownBy(() -> jdbc.update(insert, id, 2, new byte[]{1}))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, id, 0, new byte[]{1}))
                .isInstanceOf(UncategorizedSQLException.class);
        jdbc.update("DELETE FROM return_requests WHERE id = ?", id);
        assertThat(count("return_evidence_files")).isZero();
    }

    @Test
    void theSweepIndexAndTheBuyerAndStoreIndexesExist() {
        List<String> indexes = jdbc.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'return_requests'""", String.class);

        assertThat(indexes).contains("idx_return_requests_sweep", "idx_return_requests_buyer",
                "idx_return_requests_store", "uq_return_requests_order_item");
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'return_requests'
                  AND index_name = 'idx_return_requests_sweep' ORDER BY seq_in_index""", String.class))
                .containsExactly("status", "next_action_at");
    }

    @Test
    void migratingFromV26KeepsExistingOrdersAndAddsEmptyReturnTables() {
        rootJdbc("").execute("DROP DATABASE IF EXISTS " + LEGACY_DB);
        rootJdbc("").execute("CREATE DATABASE " + LEGACY_DB);
        JdbcTemplate legacy = rootJdbc(LEGACY_DB);
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + LEGACY_DB);
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target("26").load().migrate();
        legacy.update("INSERT INTO products(name, price, stock, category, active) VALUES ('Legado', 10.00, 3, 'Hogar', TRUE)");
        legacy.update("""
                INSERT INTO addresses(recipient_name, street, city, department, postal_code, phone)
                VALUES ('Ana','Calle 1','Bogotá','Cundinamarca','110111','+57 300 123-4567')""");
        legacy.update("""
                INSERT INTO orders(status, payment_status, total, store_id, address_id, shipping_method,
                    delivery_recipient_name, delivery_street, delivery_city, delivery_department, delivery_postal_code,
                    delivery_phone, transaction_id, created_at)
                SELECT 'DELIVERED', 'APPROVED', 10.00, 1, id, 'STANDARD', recipient_name, street, city, department,
                       postal_code, phone, 'legado-1', NOW(6) FROM addresses LIMIT 1""");
        legacy.update("""
                INSERT INTO order_items(order_id, product_id, product_name, quantity, unit_price, subtotal)
                SELECT o.id, p.id, p.name, 1, 10.00, 10.00 FROM orders o, products p LIMIT 1""");

        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .load().migrate();

        assertThat(legacy.queryForObject("SELECT status FROM orders WHERE transaction_id = 'legado-1'", String.class))
                .isEqualTo("DELIVERED");
        assertThat(legacy.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class)).isEqualTo(1);
        for (String table : RETURN_TABLES) {
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
    }
}
