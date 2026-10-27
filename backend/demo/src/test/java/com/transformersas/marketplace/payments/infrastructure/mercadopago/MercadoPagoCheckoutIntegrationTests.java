package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {"payments.mercadopago.access-token=test-only", "payments.mercadopago.webhook-secret=test-secret",
        "payments.mercadopago.collector-id=123", "payments.mercadopago.return-url=https://shop.example/",
        "payments.mercadopago.notification-url=https://shop.example/api/payments/mercadopago/webhook", "payments.mock-enabled=false"})
class MercadoPagoCheckoutIntegrationTests extends AbstractIntegrationTest {
    @MockitoSpyBean MercadoPagoClient provider;
    @Autowired MercadoPagoCheckoutService checkouts;
    private Session buyer;
    private long accountId, address, product;

    @AfterEach void clearAttempts() { jdbc.update("DELETE FROM payment_checkouts"); }

    private JsonNode start() throws Exception {
        buyer = sessionWithRole("mp-buyer@example.test", "COMPRADOR");
        accountId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email='mp-buyer@example.test'", Long.class);
        address = seedAddress(buyer);
        product = seedProduct(1, "Compra de prueba", 10, "95000.00");
        perform(buyer, post("/api/cart/items").contentType("application/json")
                .content(json.writeValueAsString(Map.of("productId", product, "quantity", 1)))).andExpect(status().isCreated());
        doReturn(new MercadoPagoClient.Preference("preference", "https://sandbox.mercadopago.com.co/checkout/v1/redirect?pref_id=p"))
                .when(provider).create(anyString(), any(), anyString());
        return json.readTree(perform(buyer, post("/api/payments/mercadopago/checkouts").contentType("application/json")
                .content(json.writeValueAsString(Map.of("addressId", address, "shippingMethod", "STANDARD", "couponCode", ""))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void payment(JsonNode attempt, String state) {
        doReturn(json.valueToTree(Map.of("id", 456, "external_reference", attempt.get("id").asString(),
                "collector_id", 123, "live_mode", false, "currency_id", "COP", "transaction_amount", attempt.get("total").decimalValue(),
                "status", state))).when(provider).payment("456");
    }

    @Test void approvedPaymentUsesSnapshotAndDuplicateNotificationsCreateOnlyOneOrder() throws Exception {
        var attempt = start();
        payment(attempt, "approved");
        jdbc.update("UPDATE addresses SET street='Changed after redirect' WHERE id=?", address);
        jdbc.update("UPDATE products SET price=99999 WHERE id=?", product);
        jdbc.update("UPDATE cart_items SET quantity=2 WHERE cart_id=(SELECT id FROM carts WHERE account_id=?)", accountId);
        var first = checkouts.reconcile(accountId, attempt.get("id").asString(), "456");
        checkouts.notification("456");
        assertThat(first.status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_items", java.math.BigDecimal.class)).isEqualByComparingTo("95000");
        assertThat(jdbc.queryForObject("SELECT delivery_street FROM orders", String.class)).isNotEqualTo("Changed after redirect");
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT quantity FROM cart_items", Integer.class)).isEqualTo(2);
    }

    @Test void unchangedPurchasedCartIsClearedOnlyAfterApproval() throws Exception {
        var attempt = start();
        payment(attempt, "pending");
        assertThat(checkouts.reconcile(accountId, attempt.get("id").asString(), "456").status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        payment(attempt, "approved");
        checkouts.reconcile(accountId, attempt.get("id").asString(), "456");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items", Integer.class)).isZero();
    }

    @Test void rejectedPaymentDoesNotCreateOrderOrDiscountStockAndMayBeRetried() throws Exception {
        var attempt = start();
        payment(attempt, "rejected");
        assertThat(checkouts.reconcile(accountId, attempt.get("id").asString(), "456").status()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(10);
        payment(attempt, "approved");
        assertThat(checkouts.reconcile(accountId, attempt.get("id").asString(), "456").status()).isEqualTo("APPROVED");
    }

    @Test void lateApprovalRequiresReviewInsteadOfSellingExpiredInventory() throws Exception {
        var attempt = start();
        jdbc.update("UPDATE inventory_reservations SET expires_at=?", java.time.LocalDateTime.now().minusMinutes(1));
        payment(attempt, "approved");
        assertThat(checkouts.reconcile(accountId, attempt.get("id").asString(), "456").status()).isEqualTo("REVIEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }

    @Test void foreignBuyerForgeryMissingCsrfAndLegacySimulationAreRejected() throws Exception {
        var attempt = start();
        var other = sessionWithRole("other@example.test", "COMPRADOR");
        String path = "/api/payments/mercadopago/checkouts/" + attempt.get("id").asString();
        perform(other, get(path)).andExpect(status().isNotFound());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/refresh").cookie(buyer.cookie()).contentType("application/json")
                .content("{\"paymentId\":\"456\"}")).andExpect(status().isForbidden());
        perform(buyer, post("/api/payments/process").contentType("application/json").content("{\"paymentMethod\":\"CARD\"}"))
                .andExpect(status().isGone());
        mvc.perform(post("/api/payments/mercadopago/webhook?data.id=456")).andExpect(status().isUnauthorized());
        doReturn(json.readTree("""
                {"id":456,"external_reference":"forged","collector_id":123,"live_mode":false,"currency_id":"COP","transaction_amount":1,"status":"approved"}
                """)).when(provider).payment("456");
        assertThatThrownBy(() -> checkouts.reconcile(accountId, attempt.get("id").asString(), "456"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }

    @Test void repeatedStartReusesPendingPreferenceWithoutReservingTwice() throws Exception {
        var attempt = start();
        var second = checkouts.start(accountId, new MercadoPagoCheckoutService.Purchase(address, "STANDARD", ""));
        assertThat(second.id()).isEqualTo(attempt.get("id").asString());
        verify(provider, times(1)).create(anyString(), any(), anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservations", Integer.class)).isEqualTo(1);
    }

    @Test void signedWebhookUsesProviderStateInsteadOfItsBody() throws Exception {
        var attempt = start();
        payment(attempt, "pending");
        String ts = "1742505638683", requestId = "request-123";
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String digest = java.util.HexFormat.of().formatHex(mac.doFinal(("id:456;request-id:" + requestId + ";ts:" + ts + ";")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mvc.perform(post("/api/payments/mercadopago/webhook?data.id=456")
                .header("x-request-id", requestId).header("x-signature", "ts=" + ts + ",v1=" + digest)
                .contentType("application/json").content("{\"status\":\"approved\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        assertThat(checkouts.status(accountId, attempt.get("id").asString()).status()).isEqualTo("PENDING");
    }
}
