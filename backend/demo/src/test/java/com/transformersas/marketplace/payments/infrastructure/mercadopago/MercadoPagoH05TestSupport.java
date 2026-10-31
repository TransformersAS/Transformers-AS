package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.infrastructure.persistence.repository.OrderRepositoryAdapter;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Solo pruebas: MySQL Testcontainers heredado y proveedor totalmente sustituido, sin transporte HTTP externo. */
@TestPropertySource(properties = {"payments.mock-enabled=false", "payments.mercadopago.access-token=test-only",
        "payments.mercadopago.webhook-secret=test-secret", "payments.mercadopago.collector-id=123",
        "payments.mercadopago.return-url=https://shop.example/",
        "payments.mercadopago.notification-url=https://shop.example/api/payments/mercadopago/webhook"})
abstract class MercadoPagoH05TestSupport extends AbstractIntegrationTest {
    @MockitoBean protected MercadoPagoClient provider;
    @MockitoSpyBean protected OrderRepositoryAdapter orderPersistence;
    @Autowired protected MercadoPagoCheckoutService checkouts;
    protected Session buyer;
    protected long accountId, address, product;
    protected final Map<String, JsonNode> payments = new ConcurrentHashMap<>();
    protected final AtomicBoolean failNextOrderSave = new AtomicBoolean();

    @BeforeEach void prepareCheckout() throws Exception {
        payments.clear(); failNextOrderSave.set(false);
        // Se ejercita el validador real con live_mode=false; jamás requiere consultar /users/me.
        var verifier = new MercadoPagoClient(RestClient.create(), "test-only", "test-secret", "123",
                "https://shop.example/", "https://shop.example/api/payments/mercadopago/webhook");
        when(provider.belongsToCheckout(any(), anyString())).thenAnswer(call ->
                verifier.belongsToCheckout(call.getArgument(0), call.getArgument(1)));
        when(provider.matches(any(), anyString(), any())).thenAnswer(call ->
                verifier.matches(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(provider.searchPayments(anyString(), anyInt())).thenReturn(new MercadoPagoClient.PaymentSearch(java.util.List.of(), false));
        when(provider.webhookSecret()).thenReturn("test-secret");
        when(provider.create(anyString(), any(), anyString())).thenAnswer(call ->
                new MercadoPagoClient.Preference("pref-" + call.getArgument(0),
                        "https://sandbox.mercadopago.com.co/checkout/v1/redirect?pref_id=" + call.getArgument(0)));
        when(provider.payment(anyString())).thenAnswer(call -> {
            JsonNode payment = payments.get(call.getArgument(0));
            if (payment == null) throw new AssertionError("Pago no simulado: " + call.getArgument(0));
            return payment;
        });
        OrderRepositoryAdapter target = AopTestUtils.getUltimateTargetObject(orderPersistence);
        doAnswer(call -> {
            Object saved = call.callRealMethod();
            if (failNextOrderSave.compareAndSet(true, false)) {
                throw new org.springframework.dao.DataAccessResourceFailureException("H05: fallo local después de INSERT orders");
            }
            return saved;
        }).when(target).save(any(Order.class));
        buyer = sessionWithRole("h05-buyer@example.test", "COMPRADOR");
        accountId = accountIdOf("h05-buyer@example.test");
        address = seedAddress(buyer);
        product = seedProduct(1, "Producto histórico H05", 10, "95000.00");
        perform(buyer, post("/api/cart/items").contentType("application/json")
                .content(json.writeValueAsString(Map.of("productId", product, "quantity", 2))))
                .andExpect(status().isCreated());
    }

    protected MercadoPagoCheckoutService.CheckoutStatus start() {
        return checkouts.start(accountId, new MercadoPagoCheckoutService.Purchase(address, "STANDARD", ""));
    }
    protected void payment(MercadoPagoCheckoutService.CheckoutStatus checkout, String id, String status) {
        payments.put(id, json.valueToTree(Map.of("id", id, "external_reference", checkout.id(), "collector_id", 123,
                "currency_id", "COP", "transaction_amount", checkout.total(), "status", status, "live_mode", false)));
    }
    protected int browser(String checkout, String payment) throws Exception {
        return perform(buyer, post("/api/payments/mercadopago/checkouts/{id}/refresh", checkout)
                .contentType("application/json").content(json.writeValueAsString(Map.of("paymentId", payment))))
                .andReturn().getResponse().getStatus();
    }
    protected int webhook(String payment) throws Exception {
        String requestId = UUID.randomUUID().toString();
        String timestamp = Long.toString(System.currentTimeMillis());
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String digest = HexFormat.of().formatHex(mac.doFinal(("id:" + payment + ";request-id:" + requestId + ";ts:" + timestamp + ";")
                .getBytes(StandardCharsets.UTF_8)));
        return mvc.perform(post("/api/payments/mercadopago/webhook").param("data.id", payment)
                .header("x-request-id", requestId).header("x-signature", "ts=" + timestamp + ",v1=" + digest))
                .andReturn().getResponse().getStatus();
    }
    protected int cancel(long id) throws Exception {
        return perform(buyer, post("/api/orders/{id}/cancellation", id).contentType("application/json")
                .content("{\"reasonCode\":\"CHANGED_MIND\"}")).andReturn().getResponse().getStatus();
    }
    protected int stock() { return jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product); }
    protected Map<String, Object> checkout(String id) { return jdbc.queryForMap("SELECT * FROM payment_checkouts WHERE id=?", id); }
    protected long orderId(String id) { return ((Number) checkout(id).get("order_id")).longValue(); }
    protected void assertOnePurchase(String id) {
        assertThat(count("orders")).isEqualTo(1);
        assertThat(stock()).isEqualTo(8);
        assertThat(checkout(id)).containsEntry("status", "APPROVED");
        assertThat(count("order_status_history")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT status FROM inventory_reservations", String.class)).containsExactly("CONFIRMED");
        assertThat(count("refunds")).isZero();
        assertThat(count("cart_items")).isZero();
    }
    protected void expire() {
        jdbc.update("UPDATE inventory_reservations SET expires_at=?", LocalDateTime.now().minusMinutes(1));
    }
    protected void observed(String scenario, Object detail) {
        System.out.println("H05_OBS " + scenario + " | " + detail + " | orders=" + count("orders") + " stock=" + stock()
                + " refunds=" + count("refunds") + " checkouts=" + count("payment_checkouts"));
    }
    protected record Outcome(Object value, Throwable failure) { }
    protected List<Outcome> together(Callable<?> first, Callable<?> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var entered = new CountDownLatch(2);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (var task : List.of(first, second)) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("No se abrió la barrera de inicio");
                entered.countDown();
                if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("No se solaparon ambos trabajadores");
                try { return new Outcome(task.call(), null); }
                catch (Throwable failure) { return new Outcome(null, failure); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var results = new ArrayList<Outcome>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            go.countDown(); pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).as("Ningún trabajador debe sobrevivir a la prueba").isTrue();
        }
    }
}
