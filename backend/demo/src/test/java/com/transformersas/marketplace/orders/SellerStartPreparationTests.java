package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.notifications.infrastructure.gateway.SimulatedExternalNotificationGateway;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RF-113, RF-123, A1, A3, A4 y RNF-003/038/043: confirmar la preparación de un pedido. */
class SellerStartPreparationTests extends AbstractIntegrationTest {

    @Autowired SimulatedExternalNotificationGateway externalNotices;

    private Long sellerAccountId;
    private Session seller;
    private Session otherSeller;
    private long product;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM notifications");
        externalNotices.reset();
        seedStore(2, "Otra tienda");
        seller = sellerOfStore("seller@example.com", 1);
        sellerAccountId = accountIdOf("seller@example.com");
        otherSeller = sellerOfStore("seller2@example.com", 2);
        product = seedProduct(1, "Lámpara", 7, "100.00");
    }

    private ResultActions start(long orderId) throws Exception {
        return performAsSeller(seller, 1, post("/api/seller/orders/" + orderId + "/start-preparation"));
    }

    private int historyCount(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", Integer.class, orderId);
    }

    private int statusChangeAudits(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'ORDER_STATUS_CHANGED' AND entity_id = ?",
                Integer.class, String.valueOf(orderId));
    }

    private String statusOf(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private void assertNoEffects(long orderId, String expectedStatus) {
        assertThat(statusOf(orderId)).isEqualTo(expectedStatus);
        assertThat(historyCount(orderId)).isEqualTo(1);
        assertThat(statusChangeAudits(orderId)).isZero();
        assertThat(count("notifications")).isZero();
    }

    // ---------- Flujo principal (pasos 6 a 8) ----------

    @Test
    void rf113_rf123_rnf038_confirmedOrderMovesToInPreparationWithHistoryAuditNotificationAndTheSameCorrelationId()
            throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");

        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/start-preparation")
                .header("X-Correlation-Id", "prep-corr-1"))
                .andExpect(status().isOk()).andExpect(header().string("X-Correlation-Id", "prep-corr-1"))
                .andExpect(jsonPath("$.orderId").value(order)).andExpect(jsonPath("$.status").value("IN_PREPARATION"))
                .andExpect(jsonPath("$.paymentStatus").value("APPROVED"));

        assertThat(statusOf(order)).isEqualTo("IN_PREPARATION");
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT * FROM order_status_history WHERE order_id = ? ORDER BY id", order);
        assertThat(history).hasSize(2);
        assertThat(history.get(1)).containsEntry("from_status", "CONFIRMED").containsEntry("to_status", "IN_PREPARATION")
                .containsEntry("actor_type", "SELLER").containsEntry("actor_id", sellerAccountId)
                .containsEntry("correlation_id", "prep-corr-1");
        assertThat(history.get(1).get("created_at")).isNotNull(); // fecha y responsable (RF-113)

        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM audit_events WHERE action = 'ORDER_STATUS_CHANGED'");
        assertThat(audit).containsEntry("actor_type", "SELLER").containsEntry("actor_id", sellerAccountId)
                .containsEntry("entity_type", "ORDER").containsEntry("entity_id", String.valueOf(order))
                .containsEntry("outcome", "SUCCESS").containsEntry("correlation_id", "prep-corr-1");

        Map<String, Object> notification = jdbc.queryForMap("SELECT * FROM notifications");
        assertThat(notification).containsEntry("recipient_type", "BUYER").containsEntry("type", "ORDER_IN_PREPARATION")
                .containsEntry("reference_type", "ORDER").containsEntry("reference_id", String.valueOf(order))
                .containsEntry("event_key", "order-" + order + "-IN_PREPARATION")
                .containsEntry("correlation_id", "prep-corr-1");
        // El aviso externo sale después del commit con el mismo id de correlación.
        await().atMost(Duration.ofSeconds(10)).until(() -> externalNotices.accepted().size() == 1);
        assertThat(externalNotices.accepted().get(0).correlationId()).isEqualTo("prep-corr-1");
        await().atMost(Duration.ofSeconds(10)).until(() -> "SENT".equals(
                jdbc.queryForObject("SELECT external_status FROM notifications", String.class)));
    }

    @Test
    void rnf038_aGeneratedCorrelationIdIsSharedByResponseHistoryAuditAndNotification() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");

        MvcResult result = start(order).andExpect(status().isOk()).andReturn();

        String correlation = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(correlation).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM order_status_history WHERE to_status = 'IN_PREPARATION'",
                String.class)).isEqualTo(correlation);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM audit_events", String.class)).isEqualTo(correlation);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM notifications", String.class)).isEqualTo(correlation);
    }

    // ---------- A1: pedido no válido ----------

    @Test
    void a1_orderOfAnotherStoreIsNotFoundAndStaysUnchanged() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");

        performAsSeller(otherSeller, 2, post("/api/seller/orders/" + order + "/start-preparation"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        assertNoEffects(order, "CONFIRMED");
    }

    @Test
    void a1_missingOrderIsNotFound() throws Exception {
        start(999_999).andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "estado {0}")
    @EnumSource(value = OrderStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    void a1_orderNotInConfirmedStatusIsRejectedWithAStableCodeAndNoEffects(OrderStatus current) throws Exception {
        long order = seedOrder(1, current.name(), product, 1, "100.00");

        start(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_INVALID_TRANSITION"));

        assertNoEffects(order, current.name());
    }

    @ParameterizedTest(name = "pago {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"REFUND_PENDING", "REFUNDED"})
    void a1_orderWhosePaymentIsNotApprovedIsRejected(String paymentStatus) throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        jdbc.update("UPDATE orders SET payment_status = ? WHERE id = ?", paymentStatus, order);

        start(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_APPROVED"));

        assertNoEffects(order, "CONFIRMED");
    }

    // ---------- A3: inventario inconsistente ----------

    @Test
    void a3_inconsistentInventoryRegistersAnIssueRefusesTheChangeAndIsIdempotent() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        jdbc.update("DELETE FROM products WHERE id = ?", product); // el producto ya no existe

        MvcResult first = start(order).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_INCONSISTENT"))
                .andExpect(jsonPath("$.details.issueId").isNumber()).andReturn();
        start(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTORY_INCONSISTENT"));

        assertThat(statusOf(order)).isEqualTo("CONFIRMED");
        List<Map<String, Object>> issues = jdbc.queryForList("SELECT * FROM order_issues");
        assertThat(issues).hasSize(1); // la segunda detección no duplica la novedad
        assertThat(issues.get(0)).containsEntry("order_id", order).containsEntry("type", "INVENTORY_INCONSISTENCY")
                .containsEntry("status", "OPEN").containsEntry("reported_by_type", "SYSTEM");
        assertThat((String) issues.get(0).get("description")).contains("Lámpara");
        assertThat(first.getResponse().getContentAsString()).contains(String.valueOf(issues.get(0).get("id")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'ORDER_ISSUE_REGISTERED'",
                Integer.class)).isEqualTo(1);
        assertThat(historyCount(order)).isEqualTo(1);
        assertThat(count("notifications")).isZero();
    }

    // ---------- A4: la entrega no puede modificarse ----------

    @Test
    void a4_requestsCarryingDeliveryOrShippingFieldsAreRejectedAndTheSnapshotStaysIntact() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", order);

        for (String body : new String[]{
                "{\"street\":\"Otra calle\"}", "{\"addressId\":99}", "{\"shippingMethod\":\"EXPRESS\"}",
                "{\"delivery\":{\"city\":\"Cali\"}}", "{\"phone\":\"000\"}", "{\"cualquierCosa\":1}"}) {
            performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/start-preparation")
                    .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }

        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", order)).isEqualTo(before);
        assertNoEffects(order, "CONFIRMED");
    }

    @Test
    void a4_thereIsNoEndpointToChangeTheDeliveryOrShippingMethod() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", order);

        for (String path : new String[]{"/api/seller/orders/" + order, "/api/seller/orders/" + order + "/delivery",
                "/api/seller/orders/" + order + "/address", "/api/seller/orders/" + order + "/shipping-method"}) {
            performAsSeller(seller, 1, patch(path).contentType("application/json").content("{\"city\":\"Cali\"}"))
                    .andExpect(status().is4xxClientError());
            performAsSeller(seller, 1, put(path).contentType("application/json").content("{\"city\":\"Cali\"}"))
                    .andExpect(status().is4xxClientError());
        }

        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", order)).isEqualTo(before);
    }

    @Test
    void a4_anEmptyBodyOrAnEmptyObjectAreAcceptedForTheAction() throws Exception {
        long first = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        long second = seedOrder(1, "CONFIRMED", product, 1, "100.00");

        performAsSeller(seller, 1, post("/api/seller/orders/" + first + "/start-preparation")
                .contentType("application/json").content("{}")).andExpect(status().isOk());
        start(second).andExpect(status().isOk());
    }

    // ---------- RNF-043: idempotencia y concurrencia ----------

    @Test
    void rnf043_submittingTheActionTwiceProducesASingleEffect() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");

        start(order).andExpect(status().isOk());
        start(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_INVALID_TRANSITION"));

        assertThat(statusOf(order)).isEqualTo("IN_PREPARATION");
        assertThat(historyCount(order)).isEqualTo(2);
        assertThat(statusChangeAudits(order)).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void rnf043_twoSimultaneousRequestsProduceExactlyOneSuccessAndOne409() throws Exception {
        for (int round = 0; round < 5; round++) {
            long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
            var barrier = new CyclicBarrier(2);
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await();
                        return start(order).andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            List<Integer> statuses = calls.stream().map(CompletableFuture::join).sorted().collect(Collectors.toList());

            assertThat(statuses).as("ronda %d", round).containsExactly(200, 409);
            assertThat(historyCount(order)).isEqualTo(2);
            assertThat(statusChangeAudits(order)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_key = ?", Integer.class,
                    "order-" + order + "-IN_PREPARATION")).isEqualTo(1);
        }
    }

    // ---------- RNF-003: identidad ----------

    @Test
    void rnf003_theActionRequiresAnAuthenticatedSellerWithAStore() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");

        // Anónimo con token CSRF válido: llega a la autorización y recibe 401.
        var anonymous = mvc.perform(get("/api/auth/csrf")).andReturn();
        var csrf = json.readTree(anonymous.getResponse().getContentAsString());
        mvc.perform(post("/api/seller/orders/" + order + "/start-preparation")
                        .cookie(anonymous.getResponse().getCookie("SESSION"))
                        .header(csrf.get("headerName").asString(), csrf.get("token").asString()))
                .andExpect(status().isUnauthorized());
        // Vendedor sin tienda ni cabecera.
        perform(sessionWithRole("sintienda@example.com", "VENDEDOR"), post("/api/seller/orders/" + order + "/start-preparation"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("STORE_IDENTITY_MISSING"));
        // Comprador.
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        performAsSeller(buyer, 1, post("/api/seller/orders/" + order + "/start-preparation"))
                .andExpect(status().isForbidden());

        assertNoEffects(order, "CONFIRMED");
    }
}
