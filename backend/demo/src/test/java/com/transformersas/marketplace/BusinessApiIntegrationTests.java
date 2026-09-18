package com.transformersas.marketplace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class BusinessApiIntegrationTests {
    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("business_test").withUsername("test").withPassword("test");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.ObjectMapper json;
    private jakarta.servlet.http.Cookie sessionCookie;
    private String csrfHeader;
    private String csrfToken;
    private static final String TEST_HASH = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
            .encode("BusinessTestPassword!");

    private static final String PRODUCT = """
            {"name":"Producto","description":"Descripción","price":12.50,"stock":5,"category":"Hogar"}
            """;
    private static final String ADDRESS = """
            {"recipientName":"Ana","street":"Calle 1","city":"Bogotá","department":"Bogotá",
             "postalCode":"110111","phone":"+57 300 123-4567"}
            """;

    @BeforeEach
    void clearBusinessData() throws Exception {
        jdbc.update("DELETE FROM cart_items");
        jdbc.update("DELETE FROM carts");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM addresses");
        jdbc.update("DELETE FROM SPRING_SESSION");
        jdbc.update("DELETE FROM user_account_roles");
        jdbc.update("DELETE FROM user_accounts");
        jdbc.update("INSERT INTO user_accounts(email,password_hash,status) VALUES (?,?,?)",
                "business@example.com", TEST_HASH, "ACTIVA");
        var csrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var token = json.readTree(csrf.getResponse().getContentAsString());
        var login = mvc.perform(post("/api/auth/login").cookie(csrf.getResponse().getCookie("SESSION"))
                        .param("email", "business@example.com").param("password", "BusinessTestPassword!")
                        .header(token.get("headerName").asText(), token.get("token").asText()))
                .andExpect(status().isNoContent()).andReturn();
        sessionCookie = login.getResponse().getCookie("SESSION");
        var authenticatedCsrf = mvc.perform(get("/api/auth/csrf").cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn();
        token = json.readTree(authenticatedCsrf.getResponse().getContentAsString());
        csrfHeader = token.get("headerName").asText();
        csrfToken = token.get("token").asText();
    }

    @Test
    void productCreationListAndDetailPersistRealDataAndIgnoreClientId() throws Exception {
        perform(post("/api/products").contentType("application/json")
                        .content(PRODUCT.replace("{", "{\"id\":999999,")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id", not(999999)))
                .andExpect(jsonPath("$.name").value("Producto"))
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.price").value(12.5));
        Long id = jdbc.queryForObject("SELECT id FROM products", Long.class);
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, id)).isEqualTo(5);
        perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(id));
        perform(get("/api/products/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("Hogar"));
    }

    @Test
    void zeroPriceAndStockAndInactiveProductAreAccepted() throws Exception {
        perform(post("/api/products").contentType("application/json")
                        .content(PRODUCT.replace("12.50", "0").replace("\"stock\":5", "\"stock\":0")
                                .replace("{", "{\"active\":false,")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(false));
        assertThat(jdbc.queryForObject("SELECT price FROM products", java.math.BigDecimal.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"name\":\"\"}",
            "{\"name\":\" \",\"category\":\"x\",\"price\":1,\"stock\":1}",
            "{\"name\":\"x\",\"category\":\" \",\"price\":1,\"stock\":1}",
            "{\"name\":\"x\",\"category\":\"x\",\"price\":-1,\"stock\":1}",
            "{\"name\":\"x\",\"category\":\"x\",\"price\":1,\"stock\":-1}",
            "{\"name\":\"x\",\"category\":\"x\",\"price\":null,\"stock\":1}",
            "{\"name\":\"x\",\"category\":\"x\",\"price\":1,\"stock\":null}",
            "{\"name\":\"x\",\"category\":\"x\",\"price\":1.001,\"stock\":1}"
    })
    void invalidProductsDoNotPersist(String body) throws Exception {
        error(perform(post("/api/products").contentType("application/json").content(body)), 400, "/api/products");
        assertThat(count("products")).isZero();
    }

    @Test
    void productLengthsRespectSchema() throws Exception {
        for (String field : new String[]{"name", "category", "description"}) {
            String value = switch (field) { case "name" -> "Producto"; case "category" -> "Hogar"; default -> "Descripción"; };
            error(perform(post("/api/products").contentType("application/json")
                    .content(PRODUCT.replace(value, "x".repeat(256)))), 400, "/api/products");
        }
        assertThat(count("products")).isZero();
    }

    @Test
    void missingProductReturns404() throws Exception {
        error(perform(get("/api/products/999999")), 404, "/api/products/999999");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null", "{\"stock\":\"invalid\"}"})
    void malformedRequestsHaveSafeErrors(String body) throws Exception {
        error(perform(post("/api/products").contentType("application/json").content(body)), 400, "/api/products");
        assertThat(count("products")).isZero();
    }

    @Test
    void invalidPathTypeReturns400() throws Exception {
        error(perform(get("/api/products/not-a-number")), 400, "/api/products/not-a-number");
    }

    @Test
    void cartLifecyclePersistsAndCalculatesTotals() throws Exception {
        perform(get("/api/cart")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.total").value(0));
        Long product = seedProduct(5, true);
        perform(post("/api/cart/items").contentType("application/json").content(item(product, 2)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.productId").value(product)).andExpect(jsonPath("$.subtotal").value(25));
        perform(post("/api/cart/items").contentType("application/json").content(item(product, 1)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.quantity").value(3));
        Long id = jdbc.queryForObject("SELECT id FROM cart_items", Long.class);
        assertThat(count("cart_items")).isEqualTo(1);
        perform(patch("/api/cart/items/{id}", id).contentType("application/json").content("{\"quantity\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subtotal").value(62.5));
        assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items WHERE id=?", Integer.class, id)).isEqualTo(5);
        perform(get("/api/cart")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.total").value(62.5));
        perform(delete("/api/cart/items/{id}", id)).andExpect(status().isNoContent());
        assertThat(count("cart_items")).isZero();
        assertThat(count("carts")).isEqualTo(1);
        // A cart is not an inventory reservation.
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":1}", "{\"productId\":null,\"quantity\":1}",
            "{\"productId\":1}", "{\"productId\":1,\"quantity\":null}",
            "{\"productId\":1,\"quantity\":0}", "{\"productId\":1,\"quantity\":-1}"})
    void invalidCartRequestsDoNotPersist(String body) throws Exception {
        error(perform(post("/api/cart/items").contentType("application/json").content(body)), 400, "/api/cart/items");
        assertThat(count("cart_items")).isZero();
        assertThat(count("carts")).isZero();
    }

    @Test
    void missingCartProductReturns404() throws Exception {
        error(perform(post("/api/cart/items").contentType("application/json").content(item(999999L, 1))), 404, "/api/cart/items");
        assertThat(count("carts")).isZero();
    }

    @Test
    void stockChecksIncludeExistingQuantityAndRollback() throws Exception {
        Long product = seedProduct(5, true);
        error(perform(post("/api/cart/items").contentType("application/json").content(item(product, 6))), 400, "/api/cart/items");
        assertThat(count("carts")).isZero();
        perform(post("/api/cart/items").contentType("application/json").content(item(product, 4))).andExpect(status().isCreated());
        error(perform(post("/api/cart/items").contentType("application/json").content(item(product, 2))), 400, "/api/cart/items");
        Long id = jdbc.queryForObject("SELECT id FROM cart_items", Long.class);
        error(perform(patch("/api/cart/items/{id}", id).contentType("application/json").content("{\"quantity\":6}")), 400, "/api/cart/items/" + id);
        assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items", Integer.class)).isEqualTo(4);
    }

    @Test
    void addingCannotOverflowQuantity() throws Exception {
        Long product = seedProduct(Integer.MAX_VALUE, true);
        perform(post("/api/cart/items").contentType("application/json").content(item(product, Integer.MAX_VALUE))).andExpect(status().isCreated());
        error(perform(post("/api/cart/items").contentType("application/json").content(item(product, 1))), 400, "/api/cart/items");
        assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items", Integer.class)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void inactiveProductCannotBeAddedOrUpdated() throws Exception {
        Long product = seedProduct(5, false);
        error(perform(post("/api/cart/items").contentType("application/json").content(item(product, 1))), 400, "/api/cart/items");
        jdbc.update("UPDATE products SET active=true WHERE id=?", product);
        perform(post("/api/cart/items").contentType("application/json").content(item(product, 1))).andExpect(status().isCreated());
        Long id = jdbc.queryForObject("SELECT id FROM cart_items", Long.class);
        jdbc.update("UPDATE products SET active=false WHERE id=?", product);
        error(perform(patch("/api/cart/items/{id}", id).contentType("application/json").content("{\"quantity\":2}")), 400, "/api/cart/items/" + id);
        assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":null}", "{\"quantity\":0}", "{\"quantity\":-1}"})
    void invalidUpdatesLeaveQuantityUnchanged(String body) throws Exception {
        Long product = seedProduct(5, true);
        perform(post("/api/cart/items").contentType("application/json").content(item(product, 1))).andExpect(status().isCreated());
        Long id = jdbc.queryForObject("SELECT id FROM cart_items", Long.class);
        error(perform(patch("/api/cart/items/{id}", id).contentType("application/json").content(body)), 400, "/api/cart/items/" + id);
        assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingItemsReturn404ForUpdateAndDelete() throws Exception {
        error(perform(patch("/api/cart/items/999999").contentType("application/json").content("{\"quantity\":1}")), 404, "/api/cart/items/999999");
        error(perform(delete("/api/cart/items/999999")), 404, "/api/cart/items/999999");
    }

    @Test
    void addressCreationAndListPersistAndIgnoreClientId() throws Exception {
        perform(post("/api/addresses").contentType("application/json").content(ADDRESS.replace("{", "{\"id\":999999,")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id", not(999999)))
                .andExpect(jsonPath("$.recipientName").value("Ana"));
        assertThat(jdbc.queryForObject("SELECT phone FROM addresses", String.class)).isEqualTo("+57 300 123-4567");
        perform(get("/api/addresses")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].city").value("Bogotá"));
    }

    @Test
    void postalCodeIsOptional() throws Exception {
        perform(post("/api/addresses").contentType("application/json").content(ADDRESS.replace("\"110111\"", "null")))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT postal_code FROM addresses", String.class)).isNull();
    }

    @Test
    void addressRequiredFieldsLengthsAndFormatsAreValidated() throws Exception {
        for (String value : new String[]{"Ana", "Calle 1", "Bogotá", "+57 300 123-4567"}) {
            error(perform(post("/api/addresses").contentType("application/json").content(ADDRESS.replace(value, " "))), 400, "/api/addresses");
        }
        for (String value : new String[]{"Ana", "Calle 1", "Bogotá"}) {
            error(perform(post("/api/addresses").contentType("application/json").content(ADDRESS.replace(value, "x".repeat(256)))), 400, "/api/addresses");
        }
        for (String body : new String[]{"{}", ADDRESS.replace("110111", "1".repeat(51)),
                ADDRESS.replace("+57 300 123-4567", "1".repeat(51)), ADDRESS.replace("110111", "<>"),
                ADDRESS.replace("+57 300 123-4567", "not a phone")}) {
            error(perform(post("/api/addresses").contentType("application/json").content(body)), 400, "/api/addresses");
        }
        assertThat(count("addresses")).isZero();
    }

    @Test
    void databaseRejectsInvalidValuesEvenWithoutHttpValidation() {
        Long product = seedProduct(5, true);
        assertThatThrownBy(() -> jdbc.update("UPDATE products SET price=-1 WHERE id=?", product))
                .isInstanceOf(UncategorizedSQLException.class)
                .satisfies(error -> assertThat(((UncategorizedSQLException) error).getSQLException().getErrorCode())
                        .isEqualTo(3819));
        assertThatThrownBy(() -> jdbc.update("UPDATE products SET stock=-1 WHERE id=?", product))
                .isInstanceOf(UncategorizedSQLException.class)
                .satisfies(error -> assertThat(((UncategorizedSQLException) error).getSQLException().getErrorCode())
                        .isEqualTo(3819));
        jdbc.update("INSERT INTO carts VALUES ()");
        Long cart = jdbc.queryForObject("SELECT id FROM carts", Long.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity) VALUES (?,?,0)", cart, product))
                .isInstanceOf(UncategorizedSQLException.class)
                .satisfies(error -> assertThat(((UncategorizedSQLException) error).getSQLException().getErrorCode())
                        .isEqualTo(3819));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IN ('1','2','3')", Integer.class)).isEqualTo(3);
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request.cookie(sessionCookie).header(csrfHeader, csrfToken));
    }

    private Long seedProduct(int stock, boolean active) {
        jdbc.update("INSERT INTO products(name,price,stock,category,active) VALUES ('Producto',12.50,?,'Hogar',?)", stock, active);
        return jdbc.queryForObject("SELECT MAX(id) FROM products", Long.class);
    }

    private String item(Long product, int quantity) {
        return "{\"productId\":" + product + ",\"quantity\":" + quantity + "}";
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void error(ResultActions result, int code, String path) throws Exception {
        result.andExpect(status().is(code)).andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.error", not(emptyOrNullString())))
                .andExpect(jsonPath("$.message", not(emptyOrNullString())))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.trace").doesNotExist()).andExpect(jsonPath("$.exception").doesNotExist());
    }
}
