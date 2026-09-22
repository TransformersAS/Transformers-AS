package com.transformersas.marketplace.auth;

import com.transformersas.marketplace.payments.domain.repository.PaymentGateway;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real authenticated JDBC sessions and MySQL; no injected authorities or mocked ownership repositories. */
class BuyerIsolationIntegrationTests extends AbstractIntegrationTest {
    @MockitoSpyBean PaymentGateway gateway;
    private Session a;
    private Session b;
    private long accountA;
    private long accountB;
    private long product;

    @BeforeEach
    void buyers() throws Exception {
        accountA = createAccount("a@example.com", "COMPRADOR");
        accountB = createAccount("b@example.com", "COMPRADOR");
        a = login("a@example.com");
        b = login("b@example.com");
        product = seedProduct(1, "Producto", 30, "100.00");
        clearInvocations(gateway);
    }

    private List<MockHttpServletRequestBuilder> buyerOperations() {
        return List.of(get("/api/cart"), head("/api/cart"), post("/api/cart/items"),
                patch("/api/cart/items/999"), delete("/api/cart/items/999"),
                get("/api/addresses"), head("/api/addresses"), post("/api/addresses"),
                post("/api/checkout/preview"), post("/api/reservations/cart"), post("/api/payments/process"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "SOPORTE", "ADMIN", "NONE"})
    void onlyActiveBuyerCanUseEveryBuyerOperation(String role) throws Exception {
        // Possessing COMPRADOR is insufficient when another role is selected (or none is selected).
        createAccount("multi@example.com", "COMPRADOR", "VENDEDOR", "SOPORTE", "ADMIN");
        Session multi = login("multi@example.com");
        if (!role.equals("NONE")) {
            perform(multi, put("/api/auth/active-role").contentType("application/json")
                    .content("{\"role\":\"" + role + "\"}")).andExpect(status().isOk());
        }
        for (var request : buyerOperations()) {
            perform(multi, request.contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(count("carts")).isZero();
        assertThat(count("addresses")).isZero();
        assertThat(count("inventory_reservations")).isZero();
        verifyNoInteractions(gateway);
        perform(multi, put("/api/auth/active-role").contentType("application/json")
                .content("{\"role\":\"COMPRADOR\"}")).andExpect(status().isOk());
        perform(multi, get("/api/cart")).andExpect(status().isOk());
        perform(multi, get("/api/addresses")).andExpect(status().isOk());
        perform(multi, put("/api/auth/active-role").contentType("application/json")
                .content("{\"role\":\"VENDEDOR\"}")).andExpect(status().isOk());
        perform(multi, get("/api/cart")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsAndBuyerWritesWithoutCsrfRemainRejected() throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var token = json.readTree(response.getContentAsString());
        Session anonymous = new Session(response.getCookie("SESSION"), token.get("headerName").asString(), token.get("token").asString());
        for (var request : buyerOperations()) {
            perform(anonymous, request.contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        for (String path : List.of("/api/cart/items", "/api/addresses", "/api/checkout/preview",
                "/api/reservations/cart", "/api/payments/process")) {
            mvc.perform(post(path).cookie(a.cookie()).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(count("carts")).isZero();
        verifyNoInteractions(gateway);
    }

    @Test
    void cartsAndItemMutationsAreScopedToSessionDespiteSpoofedSelectors() throws Exception {
        long itemB = add(b, 2);
        JsonNode cartA = body(a, get("/api/cart").param("accountId", String.valueOf(accountB))
                .header("X-Account-Id", accountB));
        assertThat(cartA.get("items").size()).isZero();
        long itemA = add(a, 1);
        assertThat(itemA).isNotEqualTo(itemB);
        for (long foreign : List.of(itemB, Long.MAX_VALUE)) {
            perform(a, patch("/api/cart/items/{id}", foreign).contentType("application/json")
                    .content("{\"quantity\":5,\"accountId\":" + accountB + "}"))
                    .andExpect(status().isNotFound());
            perform(a, delete("/api/cart/items/{id}", foreign).header("X-Account-Id", accountB))
                    .andExpect(status().isNotFound());
        }
        perform(a, patch("/api/cart/items/{id}", itemA).contentType("application/json").content("{\"quantity\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(3));
        perform(a, delete("/api/cart/items/{id}", itemA)).andExpect(status().isNoContent());
        assertThat(body(a, get("/api/cart")).get("items").size()).isZero();
        assertThat(body(b, get("/api/cart")).get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT account_id FROM carts ORDER BY account_id", Long.class))
                .containsExactly(accountA, accountB);
    }

    @Test
    void addressesAndCheckoutAreScopedAndClientCannotAssignAnOwner() throws Exception {
        long addressB = address(b, accountA);
        long addressA = address(a, accountB);
        assertThat(jdbc.queryForObject("SELECT account_id FROM addresses WHERE id=?", Long.class, addressA)).isEqualTo(accountA);
        assertThat(jdbc.queryForObject("SELECT account_id FROM addresses WHERE id=?", Long.class, addressB)).isEqualTo(accountB);
        perform(a, get("/api/addresses").param("accountId", String.valueOf(accountB)).header("X-Account-Id", accountB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(addressA));
        perform(b, get("/api/addresses")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(addressB));
        add(a, 1);
        add(b, 3);
        for (long foreign : List.of(addressB, Long.MAX_VALUE)) {
            perform(a, post("/api/checkout/preview").contentType("application/json").content(checkout(foreign)))
                    .andExpect(status().isNotFound());
        }
        perform(a, post("/api/checkout/preview").contentType("application/json").content(checkout(addressA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subtotal").value(100));
        perform(b, post("/api/checkout/preview").contentType("application/json").content(checkout(addressB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subtotal").value(300));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CARD", "TEST_REJECT", "TEST_PENDING"})
    void foreignOrMixedReservationsAndForeignAddressAreRejectedBeforeGateway(String method) throws Exception {
        long addressA = address(a, accountA);
        long addressB = address(b, accountB);
        add(a, 1);
        add(b, 2);
        long reservationA = reserve(a).get(0).get("id").asLong();
        long reservationB = reserve(b).get(0).get("id").asLong();
        for (List<Long> ids : List.of(List.of(reservationB), List.of(reservationA, reservationB), List.of(Long.MAX_VALUE))) {
            perform(a, payment(method, ids, addressA)).andExpect(status().isNotFound());
        }
        perform(a, payment(method, List.of(reservationA), addressB)).andExpect(status().isNotFound());
        verifyNoInteractions(gateway);
        assertThat(count("orders")).isZero();
        assertThat(jdbc.queryForList("SELECT status FROM inventory_reservations", String.class))
                .containsOnly("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(30);
        assertThat(count("cart_items")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CARD", "TEST_REJECT", "TEST_PENDING"})
    void ownPaymentOnlyAffectsOwnReservationsCartAndOrder(String method) throws Exception {
        long addressA = address(a, accountA);
        add(b, 3); // B's cart exists first: selecting the first/global cart would fail these assertions.
        long reservationB = reserve(b).get(0).get("id").asLong();
        add(a, 1);
        long reservationA = reserve(a).get(0).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT account_id FROM inventory_reservations WHERE id=?", Long.class, reservationA))
                .isEqualTo(accountA);
        perform(a, payment(method, List.of(reservationA), addressA)).andExpect(status().isOk());
        verify(gateway).process(method);
        assertThat(body(b, get("/api/cart")).get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservations WHERE id=?", String.class, reservationB))
                .isEqualTo("ACTIVE");
        String expected = method.equals("CARD") ? "CONFIRMED" : method.equals("TEST_REJECT") ? "RELEASED" : "ACTIVE";
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservations WHERE id=?", String.class, reservationA))
                .isEqualTo(expected);
        if (method.equals("CARD")) {
            assertThat(body(a, get("/api/cart")).get("items").size()).isZero();
            assertThat(jdbc.queryForObject("SELECT account_id FROM orders", Long.class)).isEqualTo(accountA);
            assertThat(jdbc.queryForObject("SELECT address_id FROM orders", Long.class)).isEqualTo(addressA);
            assertThat(jdbc.queryForObject("SELECT quantity FROM order_items", Integer.class)).isEqualTo(1);
            long order = jdbc.queryForObject("SELECT id FROM orders", Long.class);
            perform(b, get("/api/orders/{id}", order)).andExpect(status().isNotFound());
            assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(29);
        } else {
            assertThat(count("orders")).isZero();
            assertThat(body(a, get("/api/cart")).get("items").size()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(30);
        }
    }

    @Test
    void legacyUnownedDataCannotBeAdoptedOrPurchasedAndOtherCartIsNotAFallback() throws Exception {
        jdbc.update("INSERT INTO carts() VALUES ()");
        long legacyCart = jdbc.queryForObject("SELECT id FROM carts WHERE account_id IS NULL", Long.class);
        jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity) VALUES (?,?,4)", legacyCart, product);
        long legacyItem = jdbc.queryForObject("SELECT id FROM cart_items WHERE cart_id=?", Long.class, legacyCart);
        long legacyAddress = seedAddress();
        jdbc.update("INSERT INTO inventory_reservations(product_id,quantity,status,created_at,expires_at) VALUES (?,1,'ACTIVE',NOW(),DATE_ADD(NOW(), INTERVAL 10 MINUTE))", product);
        long legacyReservation = jdbc.queryForObject("SELECT id FROM inventory_reservations WHERE account_id IS NULL", Long.class);
        long addressA = address(a, accountA);
        add(b, 2);
        perform(a, post("/api/reservations/cart")).andExpect(status().isBadRequest());
        perform(a, post("/api/checkout/preview").contentType("application/json").content(checkout(addressA)))
                .andExpect(status().isBadRequest());
        assertThat(body(a, get("/api/cart")).get("items").size()).isZero();
        perform(a, get("/api/addresses")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        perform(a, delete("/api/cart/items/{id}", legacyItem)).andExpect(status().isNotFound());
        add(a, 1);
        perform(a, post("/api/checkout/preview").contentType("application/json").content(checkout(legacyAddress)))
                .andExpect(status().isNotFound());
        perform(a, payment("CARD", List.of(legacyReservation), addressA)).andExpect(status().isNotFound());
        verifyNoInteractions(gateway);
        assertThat(jdbc.queryForObject("SELECT account_id FROM carts WHERE id=?", Long.class, legacyCart)).isNull();
    }

    private JsonNode body(Session session, MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(perform(session, request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private long add(Session buyer, int quantity) throws Exception {
        return json.readTree(perform(buyer, post("/api/cart/items").contentType("application/json")
                .content("{\"productId\":" + product + ",\"quantity\":" + quantity + ",\"accountId\":" + accountB + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long address(Session buyer, long spoofedOwner) throws Exception {
        return json.readTree(perform(buyer, post("/api/addresses").contentType("application/json").content("""
                {"recipientName":"Ana","street":"Calle 1","city":"Bogotá","department":"Bogotá","phone":"3001234567","accountId":%d}
                """.formatted(spoofedOwner))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asLong();
    }

    private JsonNode reserve(Session buyer) throws Exception {
        return body(buyer, post("/api/reservations/cart").header("X-Account-Id", accountB).param("accountId", String.valueOf(accountB)));
    }

    private String checkout(long address) {
        return "{\"addressId\":" + address + ",\"shippingMethod\":\"STANDARD\",\"accountId\":" + accountB + "}";
    }

    private MockHttpServletRequestBuilder payment(String method, List<Long> reservations, long address) {
        return post("/api/payments/process").contentType("application/json").content("""
                {"paymentMethod":"%s","reservationIds":%s,"addressId":%d,"shippingMethod":"STANDARD","accountId":%d}
                """.formatted(method, reservations, address, accountB));
    }
}
