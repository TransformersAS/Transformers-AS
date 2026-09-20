package com.transformersas.marketplace.stores;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V20 (CU-18): la tienda 1 sobrevive intacta y las restricciones del esquema nuevo. */
class StoresMigrationTests extends AbstractIntegrationTest {

    private static final String LEGACY_DB = "legacy_stores";
    private static final byte[] PIXELS = {1, 2, 3};

    @AfterAll
    static void dropLegacyDatabase() {
        rootJdbc("").execute("DROP DATABASE IF EXISTS " + LEGACY_DB);
    }

    private static JdbcTemplate rootJdbc(String database) {
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + database);
        return new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private void insertImage(long storeId, String kind, String contentType, int size) {
        jdbc.update("""
                INSERT INTO store_images(store_id, kind, content_type, size_bytes, sha256, data)
                VALUES (?, ?, ?, ?, REPEAT('a', 64), ?)""", storeId, kind, contentType, size, PIXELS);
    }

    @Test
    void storeOneKeepsItsDataAndGetsDefaultsWhenMigratingFromV19() {
        rootJdbc("").execute("DROP DATABASE IF EXISTS " + LEGACY_DB);
        rootJdbc("").execute("CREATE DATABASE " + LEGACY_DB);
        JdbcTemplate legacy = rootJdbc(LEGACY_DB);
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + LEGACY_DB);

        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target("19").load().migrate();
        legacy.update("INSERT INTO products(name, price, stock, category, active) VALUES ('Legado', 10.00, 3, 'Hogar', TRUE)");
        Map<String, Object> before = legacy.queryForMap("SELECT id, name, created_at FROM stores WHERE id = 1");
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .load().migrate();

        Map<String, Object> store = legacy.queryForMap("SELECT * FROM stores WHERE id = 1");
        assertThat(store).containsEntry("id", 1L).containsEntry("name", "Tienda principal")
                .containsEntry("created_at", before.get("created_at"))
                .containsEntry("return_window_days", 30).containsEntry("status", "ACTIVE")
                .containsEntry("version", 0L);
        assertThat(store.get("owner_account_id")).isNull();
        assertThat(store.get("description")).isNull();
        assertThat(store.get("contact_email")).isNull();
        assertThat(store.get("contact_phone")).isNull();
        assertThat(store.get("business_hours")).isNull();
        assertThat(store.get("policy_text")).isNull();
        assertThat(store.get("status_reason")).isNull();
        assertThat(legacy.queryForList("SELECT method FROM store_shipping_methods WHERE store_id = 1", String.class))
                .containsExactlyInAnyOrder("STANDARD", "EXPRESS");
        assertThat(legacy.queryForObject("SELECT store_id FROM products WHERE name = 'Legado'", Long.class))
                .isEqualTo(1L);
        assertThat(legacy.queryForObject("SELECT COUNT(*) FROM store_images", Integer.class)).isZero();
    }

    @Test
    void storeOneFromAFreshDatabaseHasTheSameBaseline() {
        assertThat(jdbc.queryForMap("SELECT id, name, status FROM stores WHERE id = 1"))
                .containsEntry("name", "Tienda principal").containsEntry("status", "ACTIVE");
        assertThat(jdbc.queryForList("SELECT method FROM store_shipping_methods WHERE store_id = 1", String.class))
                .containsExactlyInAnyOrder("STANDARD", "EXPRESS");
    }

    @Test
    void storeNamesAreUniqueIgnoringCaseAndAccents() {
        seedStore(2, "Café Ñandú");

        assertThatThrownBy(() -> seedStore(3, "CAFÉ ÑANDÚ")).hasMessageContaining("uk_stores_name");
        assertThatThrownBy(() -> seedStore(3, "cafe nandu")).hasMessageContaining("uk_stores_name");
        assertThatThrownBy(() -> seedStore(3, "TIENDA PRINCIPAL")).hasMessageContaining("uk_stores_name");
        seedStore(3, "Café Ñandú Norte");
    }

    @Test
    void returnWindowCannotBeBelowThirtyDays() {
        assertThatThrownBy(() -> jdbc.update("UPDATE stores SET return_window_days = 29 WHERE id = 1"))
                .hasMessageContaining("chk_stores_return_window");
        jdbc.update("UPDATE stores SET return_window_days = 45 WHERE id = 1");
        assertThat(jdbc.queryForObject("SELECT return_window_days FROM stores WHERE id = 1", Integer.class))
                .isEqualTo(45);
    }

    @Test
    void statusIsRestrictedToKnownValuesAndNonActiveStatesNeedAReason() {
        assertThatThrownBy(() -> jdbc.update("UPDATE stores SET status = 'CERRADA', status_reason = 'x' WHERE id = 1"))
                .hasMessageContaining("chk_stores_status");
        assertThatThrownBy(() -> jdbc.update("UPDATE stores SET status = 'SUSPENDED' WHERE id = 1"))
                .hasMessageContaining("chk_stores_status_reason");
        jdbc.update("UPDATE stores SET status = 'RESTRICTED', status_reason = 'Reclamaciones pendientes' WHERE id = 1");
        assertThat(jdbc.queryForObject("SELECT status_reason FROM stores WHERE id = 1", String.class))
                .isEqualTo("Reclamaciones pendientes");
    }

    @Test
    void anAccountOwnsAtMostOneStoreAndTheOwnerMustExist() {
        long account = createAccount("owner@example.com", "VENDEDOR");
        seedStore(2, "Otra tienda");
        assignStoreOwner(1, account);

        assertThatThrownBy(() -> assignStoreOwner(2, account)).hasMessageContaining("uk_stores_owner");
        assertThatThrownBy(() -> assignStoreOwner(2, 999_999)).hasMessageContaining("fk_stores_owner");
    }

    @Test
    void imagesAreOnePerKindWithAllowedTypesAndBoundedTo5MiB() {
        insertImage(1, "LOGO", "image/png", 3);
        insertImage(1, "PORTADA", "image/jpeg", 5_242_880);

        assertThatThrownBy(() -> insertImage(1, "LOGO", "image/jpeg", 3)).hasMessageContaining("Duplicate entry");
        assertThatThrownBy(() -> insertImage(1, "BANNER", "image/png", 3)).hasMessageContaining("chk_store_images_kind");
        jdbc.update("DELETE FROM store_images");
        assertThatThrownBy(() -> insertImage(1, "LOGO", "image/gif", 3))
                .hasMessageContaining("chk_store_images_content_type");
        assertThatThrownBy(() -> insertImage(1, "LOGO", "image/png", 5_242_881))
                .hasMessageContaining("chk_store_images_size");
        assertThatThrownBy(() -> insertImage(1, "LOGO", "image/png", 0))
                .hasMessageContaining("chk_store_images_size");
        assertThatThrownBy(() -> insertImage(999, "LOGO", "image/png", 3))
                .hasMessageContaining("fk_store_images_store");
    }

    @Test
    void shippingMethodsAreUniquePerStoreAndBelongToAnExistingStore() {
        seedStore(2, "Otra tienda");
        jdbc.update("INSERT INTO store_shipping_methods(store_id, method) VALUES (2, 'STANDARD')");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO store_shipping_methods(store_id, method) VALUES (2, 'STANDARD')"))
                .hasMessageContaining("Duplicate entry");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO store_shipping_methods(store_id, method) VALUES (999, 'EXPRESS')"))
                .hasMessageContaining("fk_store_shipping_methods_store");
        List<String> store2 = jdbc.queryForList("SELECT method FROM store_shipping_methods WHERE store_id = 2", String.class);
        assertThat(store2).containsExactly("STANDARD");
    }
}
