package com.transformersas.marketplace.demo;

import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La base arranca con los datos de demostración y cada cuenta puede usarlos de inmediato por la API, igual que desde
 * el frontend. MySQL propio: estos datos no deben contaminar la base que comparten las demás suites.
 */
@SpringBootTest(properties = "marketplace.demo-data.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
class DemoDataLoaderIntegrationTests {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");

    private static final String PASSWORD = "MarketplaceDemo123!";
    private static final List<String> TABLES = List.of("user_accounts", "user_account_roles", "stores", "products",
            "product_attribute_values", "addresses", "orders", "order_items", "order_status_history", "shipments",
            "shipment_tracking_events", "return_requests", "return_events", "return_shipments",
            "return_tracking_events", "claims");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired DemoDataLoader loader;

    record Session(Cookie cookie, String header, String token) {
        MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request) {
            return request.cookie(cookie).header(header, token);
        }
    }

    @Test
    void theDatabaseStartsWithVerifiedAccountsOfEveryRoleAndTheCatalog() throws Exception {
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(u.email, ':', GROUP_CONCAT(r.role ORDER BY r.role)) FROM user_accounts u
                JOIN user_account_roles r ON r.account_id = u.id GROUP BY u.id ORDER BY u.email""", String.class))
                .containsExactly("admin.demo@example.com:ADMIN", "comprador.demo@example.com:COMPRADOR",
                        "demo@marketplace.local:COMPRADOR,VENDEDOR", "soporte.demo@example.com:SOPORTE",
                        "vendedor.demo@example.com:VENDEDOR");
        assertThat(count("user_accounts WHERE email_verified_at IS NULL")).isZero();

        // Cada rol entra con la contraseña documentada y queda con su rol activo.
        for (String[] account : new String[][]{{"soporte.demo@example.com", "SOPORTE"},
                {"admin.demo@example.com", "ADMIN"}, {"comprador.demo@example.com", "COMPRADOR"}}) {
            mvc.perform(login(account[0]).apply(get("/api/auth/me"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.activeRole").value(account[1]));
        }

        assertThat(count("products WHERE store_id = 1")).isEqualTo(10);
        assertThat(count("products p JOIN stores s ON s.id = p.store_id WHERE s.name = 'Tienda vendedor demo'"))
                .isEqualTo(3);
        assertThat(count("products WHERE store_id = 1 AND brand_id IS NULL")).isZero();
        assertThat(jdbc.queryForObject("SELECT u.email FROM stores s JOIN user_accounts u ON u.id = s.owner_account_id "
                + "WHERE s.id = 1", String.class)).isEqualTo("demo@marketplace.local");
    }

    @Test
    void theBuyerSeesOrdersInEveryStateWithTrackingReturnsAndAClaim() throws Exception {
        var buyer = login("comprador.demo@example.com");
        mvc.perform(buyer.apply(get("/api/orders"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9));

        // En camino: el seguimiento muestra lo que ya informó el proveedor y se sigue consultando.
        mvc.perform(buyer.apply(get("/api/orders/" + order("demo-datos-04") + "/tracking"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.tracking").value(true))
                .andExpect(jsonPath("$.events[*].type", contains("PICKED_UP", "IN_TRANSIT")));

        // Entregado y sin devolución: queda disponible para solicitarla en vivo (CU-19).
        mvc.perform(buyer.apply(get("/api/return-requests/eligible-orders"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.orderId == " + order("demo-datos-05") + ")].lines[0].eligible")
                        .value(hasItem(true)));

        // Devolución aprobada con su retorno registrado, lista para seguirlo (CU-25).
        long returnId = jdbc.queryForObject("SELECT r.id FROM return_requests r JOIN orders o ON o.id = r.order_id "
                + "WHERE o.transaction_id = 'demo-datos-06'", Long.class);
        mvc.perform(buyer.apply(get("/api/returns/" + returnId + "/tracking"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKUP_PENDING"))
                .andExpect(jsonPath("$.trackingCode").value("TRK-R" + returnId));
        mvc.perform(buyer.apply(get("/api/return-requests/" + returnId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.returnMethodCode").value("PICKUP"));

        mvc.perform(buyer.apply(get("/api/claims"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void eachSellerManagesOnlyTheirOwnStore() throws Exception {
        var seller = login("demo@marketplace.local");
        mvc.perform(seller.apply(put("/api/auth/active-role").contentType("application/json")
                .content("{\"role\":\"VENDEDOR\"}"))).andExpect(status().is2xxSuccessful());
        mvc.perform(seller.apply(get("/api/seller/orders").param("status", "DELIVERED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(seller.apply(get("/api/seller/return-requests"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // El segundo vendedor tiene su tienda propia, sin pedidos todavía.
        var neighbour = login("vendedor.demo@example.com");
        mvc.perform(neighbour.apply(get("/api/seller/orders"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void startingAgainNeitherDuplicatesNorChangesAnything() {
        Map<String, Long> before = counts();
        loader.run(new DefaultApplicationArguments());
        loader.run(new DefaultApplicationArguments());
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void twoReplicasStartingAtOnceLoadOneAfterTheOther() throws Exception {
        Map<String, Long> before = counts();
        // Las dos réplicas de Swarm arrancan a la vez: el candado las pone en fila y ninguna falla por interbloqueo.
        try (ExecutorService replicas = Executors.newFixedThreadPool(2)) {
            List<Future<?>> starts = List.of(replicas.submit(() -> loader.run(new DefaultApplicationArguments())),
                    replicas.submit(() -> loader.run(new DefaultApplicationArguments())));
            for (Future<?> start : starts) {
                start.get(2, TimeUnit.MINUTES);
            }
        }
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void aReplicaThatCannotGetItsTurnFailsWithAClearReason() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.createStatement().execute("SELECT GET_LOCK('" + DemoDataLoader.LOCK + "', 0)");
            var impatient = new DemoDataLoader(jdbc, DemoDataLoader.SCRIPT, 1);
            assertThatThrownBy(() -> impatient.run(new DefaultApplicationArguments()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no terminó de cargar");
            other.createStatement().execute("SELECT RELEASE_LOCK('" + DemoDataLoader.LOCK + "')");
        }
    }

    @Test
    void aScriptThatFailsHalfwayLeavesNothingAndReleasesItsTurn() {
        var broken = new DemoDataLoader(jdbc, "demo-data/script-que-falla.sql", 5);
        assertThatThrownBy(() -> broken.run(new DefaultApplicationArguments())).isInstanceOf(DataAccessException.class);
        assertThat(count("products WHERE name = 'Producto que no debe quedar'")).isZero();
        // El turno quedó libre: otra carga entra sin esperar.
        loader.run(new DefaultApplicationArguments());
    }

    private Session login(String email) throws Exception {
        var anonymous = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var csrf = json.readTree(anonymous.getContentAsString());
        var authenticated = mvc.perform(post("/api/auth/login").cookie(anonymous.getCookie("SESSION"))
                        .header(csrf.get("headerName").asString(), csrf.get("token").asString())
                        .param("email", email).param("password", PASSWORD))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        Cookie cookie = authenticated.getCookie("SESSION");
        var token = json.readTree(mvc.perform(get("/api/auth/csrf").cookie(cookie)).andReturn().getResponse()
                .getContentAsString());
        return new Session(cookie, token.get("headerName").asString(), token.get("token").asString());
    }

    private long order(String transaction) {
        return jdbc.queryForObject("SELECT id FROM orders WHERE transaction_id = ?", Long.class, transaction);
    }

    private long count(String from) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + from, Long.class);
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        TABLES.forEach(table -> counts.put(table, count(table)));
        return counts;
    }
}
