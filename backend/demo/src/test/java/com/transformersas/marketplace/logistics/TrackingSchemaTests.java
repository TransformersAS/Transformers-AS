package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.support.AbstractTrackingTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migraciones V20 (seguimiento de pedidos) y V21 (seguimiento de devoluciones): los envíos anteriores siguen activos
 * y las restricciones de la BD garantizan la idempotencia y los valores permitidos aunque el código falle.
 */
class TrackingSchemaTests extends AbstractTrackingTest {

    private static final String LEGACY_DB = "legacy_tracking";

    @AfterAll
    static void dropLegacyDatabase() {
        rootJdbc("").execute("DROP DATABASE IF EXISTS " + LEGACY_DB);
    }

    private static JdbcTemplate rootJdbc(String database) {
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + database);
        return new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    @Test
    void bothMigrationsAreApplied() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IN ('20','21') AND success = 1",
                String.class)).containsExactlyInAnyOrder("20", "21");
    }

    @Test
    void shipmentsCreatedBeforeV20StayActiveAndNeverPolled() {
        rootJdbc("").execute("DROP DATABASE IF EXISTS " + LEGACY_DB);
        rootJdbc("").execute("CREATE DATABASE " + LEGACY_DB);
        JdbcTemplate legacy = rootJdbc(LEGACY_DB);
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + LEGACY_DB);

        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target("19").load().migrate();
        legacy.update("""
                INSERT INTO addresses(recipient_name, street, city, department, postal_code, phone)
                VALUES ('Luis','Carrera 7','Cali','Valle',NULL,'300')""");
        legacy.update("""
                INSERT INTO orders(status, payment_status, total, store_id, address_id, shipping_method, delivery_recipient_name,
                                   delivery_street, delivery_city, delivery_department, delivery_phone, transaction_id, created_at)
                SELECT 'READY_FOR_DISPATCH', 'APPROVED', 10, 1, id, 'STANDARD', 'Luis', 'Carrera 7', 'Cali', 'Valle', '300',
                       'legacy-ship', NOW(6) FROM addresses""");
        legacy.update("""
                INSERT INTO shipments(order_id, provider_shipment_id, tracking_code, idempotency_key, status, created_at)
                SELECT id, 'SHP-1', 'TRK-1', 'order-1', 'CREATED', NOW(6) FROM orders""");

        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration").load().migrate();

        var shipment = legacy.queryForMap("SELECT tracking_active, last_polled_at, poll_failures FROM shipments");
        assertThat(shipment).containsEntry("tracking_active", true).containsEntry("poll_failures", 0);
        assertThat(shipment.get("last_polled_at")).isNull();
        assertThat(legacy.queryForObject("SELECT COUNT(*) FROM shipment_tracking_events", Integer.class)).isZero();
    }

    @Test
    void aTrackingEventIsUniquePerShipmentAndProviderEvent() {
        long order = shippedOrder(createAccount("b@example.com", "COMPRADOR"), "READY_FOR_DISPATCH");
        long shipment = shipmentId(order);
        String insert = "INSERT INTO shipment_tracking_events(shipment_id, order_id, provider_event_id, event_type, occurred_at, "
                + "received_at, source, outcome, correlation_id) VALUES (?, ?, ?, 'PICKED_UP', NOW(6), NOW(6), ?, ?, 'c')";
        jdbc.update(insert, shipment, order, "evt-1", "WEBHOOK", "APPLIED");

        assertThatThrownBy(() -> jdbc.update(insert, shipment, order, "evt-1", "POLLING", "APPLIED"))
                .isInstanceOf(DataAccessException.class);
        jdbc.update(insert, shipment, order, "evt-2", "POLLING", "OUT_OF_ORDER"); // otro evento sí
        assertThatThrownBy(() -> jdbc.update(insert, shipment, order, "evt-3", "CARRIER", "APPLIED"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(insert, shipment, order, "evt-4", "WEBHOOK", "MAYBE"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(insert, 987654, order, "evt-5", "WEBHOOK", "APPLIED"))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("shipment_tracking_events")).isEqualTo(2);
    }

    @Test
    void returnShipmentsEnforceUniquenessOwnersAndAllowedValues() {
        Long buyer = createAccount("b@example.com", "COMPRADOR");
        returnShipment(1, buyer, 1, "PICKUP_PENDING", 0);

        String insert = "INSERT INTO return_shipments(return_id, buyer_account_id, store_id, provider_return_id, tracking_code, "
                + "status, failed_pickups, created_at, updated_at) VALUES (?, ?, ?, ?, 'T', ?, ?, NOW(6), NOW(6))";
        assertThatThrownBy(() -> jdbc.update(insert, 1, buyer, 1, "otra-ref", "PICKUP_PENDING", 0))
                .isInstanceOf(DataAccessException.class); // misma devolución
        assertThatThrownBy(() -> jdbc.update(insert, 2, buyer, 1, "SIM-return-1", "PICKUP_PENDING", 0))
                .isInstanceOf(DataAccessException.class); // misma referencia logística
        assertThatThrownBy(() -> jdbc.update(insert, 3, buyer, 1, "ref-3", "LOST", 0))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(insert, 4, buyer, 1, "ref-4", "PICKUP_PENDING", -1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(insert, 5, 987654, 1, "ref-5", "PICKUP_PENDING", 0))
                .isInstanceOf(DataAccessException.class); // comprador inexistente
        assertThatThrownBy(() -> jdbc.update(insert, 6, buyer, 987654, "ref-6", "PICKUP_PENDING", 0))
                .isInstanceOf(DataAccessException.class); // tienda inexistente
        assertThat(count("return_shipments")).isEqualTo(1);
    }

    @Test
    void aReturnTimelineEventIsUniquePerReturnAndProviderEvent() {
        Long buyer = createAccount("b@example.com", "COMPRADOR");
        long shipment = returnShipment(1, buyer, 1, "PICKUP_PENDING", 0);
        String insert = "INSERT INTO return_tracking_events(return_shipment_id, provider_event_id, event_type, occurred_at, "
                + "received_at, source, outcome, correlation_id) VALUES (?, ?, 'PICKED_UP', NOW(6), NOW(6), ?, ?, 'c')";
        jdbc.update(insert, shipment, "evt-1", "SYSTEM", "APPLIED");

        assertThatThrownBy(() -> jdbc.update(insert, shipment, "evt-1", "WEBHOOK", "APPLIED"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(insert, shipment, "evt-2", "CARRIER", "APPLIED"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(insert, shipment, "evt-3", "WEBHOOK", "MAYBE"))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("return_tracking_events")).isEqualTo(1);
    }
}
