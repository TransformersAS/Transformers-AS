package com.transformersas.marketplace.support;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base de las pruebas de integración de CU-23: un único MySQL Testcontainers compartido por todas las clases
 * (y por tanto un único contexto de Spring cuando la configuración coincide), sesión real por cookie y CSRF.
 * Cada prueba parte de una base limpia (resetDatabase).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    // Contenedor singleton: arranca una vez por JVM y lo limpia Ryuk al terminar.
    @ServiceConnection
    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("cu23_test").withUsername("test").withPassword("test");

    static {
        MYSQL.start();
    }

    protected static final String PASSWORD = "Cu23TestPassword!";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    /** Tablas de negocio en orden de borrado seguro para las claves foráneas (hijas primero). */
    private static final List<String> TABLES_TO_CLEAR = List.of(
            "audit_events", "notifications", "refunds", "order_cancellations", "return_tracking_events", "return_shipments",
            "shipment_tracking_events", "shipments", "order_issues", "order_status_history", "order_items", "orders",
            "inventory_reservations", "cart_items", "carts", "products", "addresses",
            "store_images", "store_shipping_methods");

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;

    /** Cookie de sesión + token CSRF de una cuenta autenticada. */
    public record Session(Cookie cookie, String csrfHeader, String csrfToken) {
        public MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request) {
            return request.cookie(cookie).header(csrfHeader, csrfToken);
        }
    }

    @BeforeEach
    protected void resetDatabase() {
        TABLES_TO_CLEAR.forEach(table -> jdbc.update("DELETE FROM " + table));
        jdbc.update("DELETE FROM stores WHERE id <> 1");
        // La tienda 1 vuelve al estado que deja la migración; sin dueña antes de borrar las cuentas (FK).
        jdbc.update("""
                UPDATE stores SET owner_account_id = NULL, name = 'Tienda principal', description = NULL,
                    contact_email = NULL, contact_phone = NULL, business_hours = NULL, return_window_days = 30,
                    policy_text = NULL, status = 'ACTIVE', status_reason = NULL, version = 0
                WHERE id = 1""");
        jdbc.update("INSERT INTO store_shipping_methods(store_id, method) VALUES (1, 'STANDARD'), (1, 'EXPRESS')");
        jdbc.update("DELETE FROM SPRING_SESSION");
        jdbc.update("DELETE FROM user_account_roles");
        jdbc.update("DELETE FROM user_accounts");
    }

    // ---------- Cuentas y sesiones ----------

    protected Long createAccount(String email, String... roles) {
        jdbc.update("INSERT INTO user_accounts(email, password_hash, status) VALUES (?,?,'ACTIVA')", email, PASSWORD_HASH);
        Long id = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = ?", Long.class, email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_account_roles(account_id, role) VALUES (?,?)", id, role);
        }
        return id;
    }

    protected Session login(String email) throws Exception {
        var anonymous = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode token = json.readTree(anonymous.getResponse().getContentAsString());
        var login = mvc.perform(post("/api/auth/login").cookie(anonymous.getResponse().getCookie("SESSION"))
                        .param("email", email).param("password", PASSWORD)
                        .header(token.get("headerName").asString(), token.get("token").asString()))
                .andExpect(status().isNoContent()).andReturn();
        Cookie cookie = login.getResponse().getCookie("SESSION");
        var authenticated = mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn();
        JsonNode csrf = json.readTree(authenticated.getResponse().getContentAsString());
        return new Session(cookie, csrf.get("headerName").asString(), csrf.get("token").asString());
    }

    /** Crea una cuenta con un solo rol (queda activo automáticamente) e inicia sesión. */
    protected Session sessionWithRole(String email, String role) throws Exception {
        createAccount(email, role);
        return login(email);
    }

    protected ResultActions perform(Session session, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(session.apply(request));
    }

    /** Petición de vendedor: sesión autenticada más la tienda provisional (X-Store-Id, D2). */
    protected ResultActions performAsSeller(Session session, long storeId, MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(session.apply(request).header("X-Store-Id", storeId));
    }

    // ---------- Datos de negocio ----------

    protected long seedStore(long id, String name) {
        jdbc.update("INSERT INTO stores(id, name) VALUES (?,?)", id, name);
        return id;
    }

    /** Hace de la cuenta la dueña de la tienda (CU-18: una cuenta, una tienda). */
    protected void assignStoreOwner(long storeId, long accountId) {
        jdbc.update("UPDATE stores SET owner_account_id = ? WHERE id = ?", accountId, storeId);
    }

    protected long seedProduct(long storeId, String name, int stock, String price) {
        jdbc.update("INSERT INTO products(name, price, stock, category, active, store_id) VALUES (?,?,?,?,TRUE,?)",
                name, price, stock, "Hogar", storeId);
        return jdbc.queryForObject("SELECT MAX(id) FROM products", Long.class);
    }

    protected long seedAddress() {
        jdbc.update("""
                INSERT INTO addresses(recipient_name, street, city, department, postal_code, phone)
                VALUES ('Ana Comprador','Calle 1 # 2-3','Bogotá','Cundinamarca','110111','+57 300 123-4567')""");
        return jdbc.queryForObject("SELECT MAX(id) FROM addresses", Long.class);
    }

    /**
     * Inserta un pedido de una línea directamente en BD (sin pasar por el checkout), con su snapshot de entrega
     * y el registro inicial de historial. Devuelve el id del pedido.
     */
    protected long seedOrder(long storeId, String status, long productId, int quantity, String unitPrice) {
        long addressId = seedAddress();
        String transaction = "tx-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO orders(status, payment_status, total, store_id, address_id, shipping_method,
                                   delivery_recipient_name, delivery_street, delivery_city, delivery_department,
                                   delivery_postal_code, delivery_phone, transaction_id, created_at)
                SELECT ?, 'APPROVED', ? * ?, ?, id, 'STANDARD', recipient_name, street, city, department,
                       postal_code, phone, ?, NOW(6)
                FROM addresses WHERE id = ?""", status, unitPrice, quantity, storeId, transaction, addressId);
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE transaction_id = ?", Long.class, transaction);
        jdbc.update("""
                INSERT INTO order_items(order_id, product_id, product_name, quantity, unit_price, subtotal)
                SELECT ?, id, name, ?, ?, ? * ? FROM products WHERE id = ?""",
                orderId, quantity, unitPrice, unitPrice, quantity, productId);
        jdbc.update("""
                INSERT INTO order_status_history(order_id, from_status, to_status, actor_type, correlation_id, created_at)
                VALUES (?, NULL, ?, 'BUYER', 'seed', NOW(6))""", orderId, status);
        return orderId;
    }

    /**
     * Compra real de punta a punta con la sesión del comprador (carrito, reserva y pago aprobado con CARD) y
     * devuelve el id del pedido creado. La dirección se siembra por BD.
     */
    protected long checkoutOrder(Session buyer, long productId, int quantity) throws Exception {
        long address = seedAddress();
        perform(buyer, post("/api/cart/items").contentType("application/json")
                .content("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated());
        String reservations = perform(buyer, post("/api/reservations/cart")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = json.readTree(reservations).valueStream().map(node -> node.get("id").asLong()).toList();
        String payment = perform(buyer, post("/api/payments/process").contentType("application/json").content("""
                {"paymentMethod":"CARD","reservationIds":%s,"addressId":%d,"shippingMethod":"STANDARD"}"""
                .formatted(ids, address))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(payment).get("orderId").asLong();
    }

    protected int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
