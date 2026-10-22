package com.transformersas.marketplace.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El ejecutable de Windows arranca con el perfil demo y con los datos de demostración a la vez. DemoProvisioning
 * exige que sus dos cuentas tengan exactamente sus roles y su contraseña: si los datos de demostración no coincidieran,
 * el arranque fallaría. Aquí se comprueba que conviven y que el pedido de CU-11 se sigue preparando igual.
 */
@SpringBootTest(properties = "marketplace.demo-data.enabled=true")
@ActiveProfiles("demo")
@Testcontainers
class DemoDataWithDemoProfileIntegrationTests {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    @Autowired JdbcTemplate jdbc;

    @Test
    void theCu11FixtureIsPreparedOnTopOfTheDemoData() {
        Long fixture = jdbc.queryForObject("SELECT order_id FROM demo_fixture WHERE fixture_key = 'cu11'", Long.class);
        assertThat(fixture).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(o.status, ' ', u.email) FROM orders o JOIN user_accounts u ON u.id = o.account_id
                WHERE o.id = ?""", String.class, fixture)).isEqualTo("CONFIRMED comprador.demo@example.com");

        // Los nueve pedidos de demostración más el de CU-11, de la misma compradora.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE transaction_id LIKE 'demo-datos-%'",
                Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class)).isEqualTo(5);
    }
}
