package com.transformersas.marketplace.demo;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MySQL independiente: las migraciones demo nunca contaminan la base compartida de otras suites. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Testcontainers
class DemoProvisioningIntegrationTests {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DemoProvisioning provisioning;
    @Autowired com.transformersas.marketplace.cart.CartService carts;
    @Autowired com.transformersas.marketplace.reservation.InventoryReservationService reservations;
    @Autowired com.transformersas.marketplace.payments.application.usecase.ProcessPaymentUseCase payments;

    record Session(Cookie cookie, String header, String token) {
        MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request) {
            return request.cookie(cookie).header(header, token);
        }
    }

    Session login(String email) throws Exception {
        var anonymous = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var csrf = json.readTree(anonymous.getContentAsString());
        var authenticated = mvc.perform(post("/api/auth/login").cookie(anonymous.getCookie("SESSION"))
                .header(csrf.get("headerName").asString(), csrf.get("token").asString())
                .param("email", email).param("password", "MarketplaceDemo123!"))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        Cookie cookie = authenticated.getCookie("SESSION");
        var token = json.readTree(mvc.perform(get("/api/auth/csrf").cookie(cookie)).andReturn().getResponse().getContentAsString());
        return new Session(cookie, token.get("headerName").asString(), token.get("token").asString());
    }

    @Test
    void cleanStartupRealAuthenticationCancellationPersistenceAndScopedRepeatableReset() throws Exception {
        long order = jdbc.queryForObject("SELECT order_id FROM demo_fixture WHERE fixture_key='cu11'", Long.class);
        long product = jdbc.queryForObject("SELECT product_id FROM order_items WHERE order_id=?", Long.class, order);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE email_verified_at IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(99);
        provisioning.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM addresses", Integer.class)).isEqualTo(1);

        var multi = login("demo@marketplace.local");
        mvc.perform(multi.apply(get("/api/auth/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2));
        for (String role : new String[]{"VENDEDOR", "COMPRADOR"}) {
            mvc.perform(multi.apply(put("/api/auth/active-role").contentType("application/json")
                    .content("{\"role\":\"" + role + "\"}"))).andExpect(status().is2xxSuccessful());
            mvc.perform(multi.apply(get("/api/auth/me"))).andExpect(jsonPath("$.activeRole").value(role));
        }
        mvc.perform(multi.apply(get("/api/auth/sessions"))).andExpect(status().isOk());
        mvc.perform(multi.apply(get("/api/orders/" + order))).andExpect(status().isNotFound());
        mvc.perform(multi.apply(post("/api/auth/logout"))).andExpect(status().isNoContent());
        mvc.perform(multi.apply(get("/api/auth/sessions"))).andExpect(status().isUnauthorized());

        var buyer = login("comprador.demo@example.com");
        mvc.perform(buyer.apply(get("/api/orders"))).andExpect(status().isOk());
        mvc.perform(buyer.apply(get("/api/orders/" + order))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        // CSRF y OTHER se validan realmente, sin sustituir filtros ni autenticación.
        mvc.perform(post("/api/orders/" + order + "/cancellation").cookie(buyer.cookie())
                .contentType("application/json").content("{\"reasonCode\":\"OTHER\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(buyer.apply(post("/api/orders/" + order + "/cancellation").contentType("application/json")
                .content("{\"reasonCode\":\"OTHER\",\"details\":\"   \"}"))).andExpect(status().isBadRequest());
        // Otra compra real de la misma cuenta: el reset no puede confundirla con el fixture.
        long buyerId = jdbc.queryForObject("SELECT account_id FROM orders WHERE id=?", Long.class, order);
        long addressId = jdbc.queryForObject("SELECT address_id FROM orders WHERE id=?", Long.class, order);
        carts.addItem(buyerId, new com.transformersas.marketplace.cart.dto.AddCartItemRequest(product, 1));
        var reserved = reservations.reserveCart(buyerId).stream().map(r -> r.id()).toList();
        long other = payments.execute(buyerId, "CARD", reserved, addressId, "STANDARD", null).order().orderId();
        for (int cycle = 0; cycle < 2; cycle++) {
            mvc.perform(buyer.apply(post("/api/orders/" + order + "/cancellation").contentType("application/json")
                    .content("{\"reasonCode\":\"OTHER\",\"details\":\"Cancelación de demostración CU-11.\"}")))
                    .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, order)).isEqualTo("REFUNDED");
            assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE order_id=?", String.class, order)).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(99);
            mvc.perform(buyer.apply(get("/api/orders/" + order))).andExpect(jsonPath("$.status").value("CANCELLED"));
            mvc.perform(buyer.apply(post("/api/orders/" + order + "/cancellation").contentType("application/json")
                    .content("{\"reasonCode\":\"OTHER\",\"details\":\"Repetida\"}"))).andExpect(status().isConflict());
            provisioning.run(new DefaultApplicationArguments());
            provisioning.run(new DefaultApplicationArguments());
            assertThat(jdbc.queryForObject("SELECT order_id FROM demo_fixture", Long.class)).isEqualTo(order);
            assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(98);
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, other)).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds", Integer.class)).isZero();
            mvc.perform(buyer.apply(get("/api/orders/" + order))).andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
    }
}
