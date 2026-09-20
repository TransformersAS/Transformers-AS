package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
import com.transformersas.marketplace.notifications.infrastructure.gateway.SimulatedExternalNotificationGateway;
import com.transformersas.marketplace.orders.application.dto.CancelOrderCommand;
import com.transformersas.marketplace.orders.application.dto.CancelOrderResult;
import com.transformersas.marketplace.orders.application.usecase.CancelOrderUseCase;
import com.transformersas.marketplace.orders.domain.model.CancellationInitiator;
import com.transformersas.marketplace.orders.domain.model.CancellationReason;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.payments.application.dto.RefundCommand;
import com.transformersas.marketplace.payments.application.usecase.RequestRefundUseCase;
import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RF-122, RF-123, A2, D10 y RNF-003/009/015/038/043: el vendedor no puede cumplir el pedido, que se cancela por
 * completo con reposición de stock, reembolso y notificaciones, sin despacho parcial.
 */
class SellerCancelOrderTests extends AbstractIntegrationTest {

    @Autowired SimulatedRefundGateway refundGateway;
    @Autowired SimulatedLogisticsGateway logistics;
    @Autowired SimulatedExternalNotificationGateway notices;
    @Autowired RequestRefundUseCase refundUseCase;
    @Autowired CancelOrderUseCase cancelOrder;

    private Long sellerAccountId;
    private Session seller;
    private long product;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM notifications");
        refundGateway.reset();
        logistics.reset();
        notices.reset();
        sellerAccountId = createAccount("seller@example.com", "VENDEDOR");
        seller = login("seller@example.com");
        seedStore(2, "Otra tienda");
        product = seedProduct(1, "Lámpara", 5, "100.00");
    }

    private ResultActions cancel(long order, String body) throws Exception {
        return performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/cancel")
                .contentType("application/json").content(body));
    }

    private ResultActions cancel(long order) throws Exception {
        return cancel(order, "{\"reasonCode\":\"OUT_OF_STOCK\"}");
    }

    private String statusOf(long order) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order);
    }

    private String paymentOf(long order) {
        return jdbc.queryForObject("SELECT payment_status FROM orders WHERE id = ?", String.class, order);
    }

    private int stockOf(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private void assertNoEffects(long order, String expectedStatus, int expectedStock) {
        assertThat(statusOf(order)).isEqualTo(expectedStatus);
        assertThat(stockOf(product)).isEqualTo(expectedStock);
        assertThat(count("order_cancellations")).isZero();
        assertThat(count("refunds")).isZero();
        assertThat(count("order_status_history")).isEqualTo(1);
        assertThat(count("audit_events")).isZero();
        assertThat(count("notifications")).isZero();
    }

    // ---------- Flujo de imposibilidad de cumplir (A2) ----------

    @ParameterizedTest(name = "desde {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CONFIRMED", "IN_PREPARATION"})
    void rf122_a2_theSellerCancelsTheWholeOrderRestoringStockRefundingAndNotifying(String from) throws Exception {
        long order = seedOrder(1, from, product, 2, "100.00");

        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/cancel").contentType("application/json")
                .header("X-Correlation-Id", "cancel-corr-1")
                .content("{\"reasonCode\":\"PRODUCT_DAMAGED\",\"details\":\"Se rompieron ambas lámparas\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(order))
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.refund.status").value("COMPLETED"));

        assertThat(statusOf(order)).isEqualTo("CANCELLED");
        assertThat(paymentOf(order)).isEqualTo("REFUNDED");
        assertThat(stockOf(product)).isEqualTo(7); // las 2 unidades vuelven al inventario
        // Motivo registrado (postcondición 4).
        assertThat(jdbc.queryForMap("SELECT * FROM order_cancellations")).containsEntry("order_id", order)
                .containsEntry("initiator", "SELLER").containsEntry("reason_code", "PRODUCT_DAMAGED")
                .containsEntry("details", "Se rompieron ambas lámparas").containsEntry("cancelled_by_id", sellerAccountId)
                .containsEntry("correlation_id", "cancel-corr-1");
        // Reembolso completo por el total del pedido.
        Map<String, Object> refund = jdbc.queryForMap("SELECT * FROM refunds");
        assertThat(refund).containsEntry("order_id", order).containsEntry("status", "COMPLETED")
                .containsEntry("idempotency_key", "order-cancel-" + order).containsEntry("correlation_id", "cancel-corr-1");
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("200.00");
        // Sin despacho.
        assertThat(count("shipments")).isZero();
        assertThat(logistics.requestCount()).isZero();
        // Historial con fecha y responsable.
        Map<String, Object> history = jdbc.queryForMap("SELECT * FROM order_status_history WHERE to_status = 'CANCELLED'");
        assertThat(history).containsEntry("from_status", from).containsEntry("actor_type", "SELLER")
                .containsEntry("actor_id", sellerAccountId).containsEntry("correlation_id", "cancel-corr-1");
        // RNF-009: auditoría de cancelación confirmada y de solicitud y resultado del reembolso.
        assertThat(jdbc.queryForList("SELECT action FROM audit_events", String.class))
                .containsExactlyInAnyOrder("REFUND_REQUESTED", "ORDER_STATUS_CHANGED", "ORDER_CANCELLED", "REFUND_RESULT");
        assertThat(jdbc.queryForMap("SELECT * FROM audit_events WHERE action = 'ORDER_CANCELLED'"))
                .containsEntry("actor_type", "SELLER").containsEntry("outcome", "SUCCESS");
        // RNF-038: un único id de correlación en todos los registros de la operación.
        assertThat(jdbc.queryForList("SELECT DISTINCT correlation_id FROM audit_events", String.class))
                .containsExactly("cancel-corr-1");
        // RF-123: una notificación al comprador y otra a la tienda, cada una con su aviso externo.
        assertThat(jdbc.queryForList("SELECT CONCAT(recipient_type, ':', event_key) FROM notifications ORDER BY id", String.class))
                .containsExactly("BUYER:order-" + order + "-CANCELLED", "STORE:order-" + order + "-CANCELLED-STORE");
        assertThat(jdbc.queryForList("SELECT DISTINCT correlation_id FROM notifications", String.class))
                .containsExactly("cancel-corr-1");
        await().atMost(Duration.ofSeconds(10)).until(() -> notices.accepted().size() == 2);
        assertThat(notices.accepted()).allSatisfy(notice -> assertThat(notice.correlationId()).isEqualTo("cancel-corr-1"));
    }

    // ---------- A2: sin despacho parcial ----------

    @ParameterizedTest(name = "estado {0}")
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "IN_PREPARATION"}, mode = EnumSource.Mode.EXCLUDE)
    void a2_anOrderThatAlreadyLeftPreparationCannotBeCancelledByTheSeller(OrderStatus current) throws Exception {
        long order = seedOrder(1, current.name(), product, 2, "100.00");

        cancel(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_INVALID_TRANSITION"));

        assertThat(statusOf(order)).isEqualTo(current.name());
        assertThat(stockOf(product)).isEqualTo(5);
        assertThat(count("order_cancellations")).isZero();
        assertThat(count("refunds")).isZero();
    }

    @Test
    void a2_anOrderWithAShipmentCannotBeCancelled() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 2, "100.00");
        jdbc.update("""
                INSERT INTO shipments(order_id, provider_shipment_id, tracking_code, idempotency_key, status, created_at)
                VALUES (?, 'SHP-1', 'TRK-1', ?, 'CREATED', NOW(6))""", order, "order-" + order);

        cancel(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_HAS_SHIPMENT"));

        assertThat(statusOf(order)).isEqualTo("IN_PREPARATION");
        assertThat(stockOf(product)).isEqualTo(5);
        assertThat(count("order_cancellations")).isZero();
    }

    @Test
    void a2_afterTheCancellationNoDispatchOrShipmentIsPossible() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        cancel(order).andExpect(status().isOk());

        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/ready-for-dispatch")).andExpect(status().isConflict());
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/shipment")).andExpect(status().isConflict());
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/start-preparation")).andExpect(status().isConflict());

        assertThat(statusOf(order)).isEqualTo("CANCELLED");
        assertThat(logistics.requestCount()).isZero();
        assertThat(count("shipments")).isZero();
    }

    @Test
    void a3_anOrderWithAnInventoryIssueCanBeCancelledInsteadOfResolvingIt() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 2, "100.00");
        jdbc.update("DELETE FROM products WHERE id = ?", product);
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/start-preparation")).andExpect(status().isConflict());

        cancel(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(statusOf(order)).isEqualTo("CANCELLED");
        // El producto ya no existe: no hay a dónde devolver las unidades y la cancelación igual procede.
        assertThat(jdbc.queryForObject("SELECT details FROM audit_events WHERE action = 'ORDER_CANCELLED'", String.class))
                .contains("\"skippedProducts\":[" + product + "]");
        assertThat(jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("COMPLETED");
    }

    @Test
    void rf122_stockIsRestoredForEveryLineOfTheOrder() throws Exception {
        long other = seedProduct(1, "Mesa", 10, "50.00");
        long order = seedOrder(1, "IN_PREPARATION", product, 2, "100.00");
        jdbc.update("INSERT INTO order_items(order_id, product_id, product_name, quantity, unit_price, subtotal) "
                + "VALUES (?, ?, 'Mesa', 3, 50.00, 150.00)", order, other);

        cancel(order).andExpect(status().isOk());

        assertThat(stockOf(product)).isEqualTo(7);
        assertThat(stockOf(other)).isEqualTo(13);
    }

    // ---------- Validación de la solicitud ----------

    @Test
    void rf122_otherRequiresDetailsAndTheRequestIsValidated() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");

        cancel(order, "{\"reasonCode\":\"OTHER\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANCELLATION_DETAILS_REQUIRED"));
        cancel(order, "{\"reasonCode\":\"OTHER\",\"details\":\"   \"}").andExpect(status().isBadRequest());
        cancel(order, "{}").andExpect(status().isBadRequest());
        cancel(order, "{\"reasonCode\":\"INVENTADO\"}").andExpect(status().isBadRequest());
        cancel(order, "{\"reasonCode\":\"OUT_OF_STOCK\",\"details\":\"" + "x".repeat(1001) + "\"}").andExpect(status().isBadRequest());
        cancel(order, "{\"reasonCode\":\"OUT_OF_STOCK\",\"address\":\"otra\"}").andExpect(status().isBadRequest());
        cancel(order, "{\"reasonCode\":\"OUT_OF_STOCK\",\"storeId\":2}").andExpect(status().isBadRequest());
        cancel(order, "no es json").andExpect(status().isBadRequest());

        assertNoEffects(order, "CONFIRMED", 5);
        cancel(order, "{\"reasonCode\":\"OTHER\",\"details\":\"El proveedor no entregó\"}").andExpect(status().isOk());
    }

    // ---------- Reembolso: fallos y estados pendientes (no revierten la cancelación) ----------

    @Test
    void rnf045_aRefundFailureLeavesTheOrderCancelledAndTheRefundRetryable() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 2, "100.00");
        refundGateway.setMode(SimulatedRefundGateway.Mode.UNAVAILABLE);

        cancel(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.refund.status").value("FAILED"));

        assertThat(statusOf(order)).isEqualTo("CANCELLED");
        assertThat(paymentOf(order)).isEqualTo("REFUND_PENDING");
        assertThat(stockOf(product)).isEqualTo(7); // el stock quedó repuesto pese al fallo del reembolso
        assertThat(jdbc.queryForMap("SELECT status, attempts FROM refunds")).containsEntry("status", "FAILED")
                .containsEntry("attempts", 1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM refunds", String.class)).contains("no disponible");
        assertThat(jdbc.queryForObject("SELECT outcome FROM audit_events WHERE action = 'REFUND_RESULT'", String.class))
                .isEqualTo("FAILURE");
        assertThat(count("notifications")).isEqualTo(2); // las notificaciones internas siguen registradas

        // Recuperable: reintentar con la misma clave completa el mismo reembolso, sin duplicarlo.
        refundGateway.setMode(SimulatedRefundGateway.Mode.OK);
        var retried = refundUseCase.execute(new RefundCommand(order, new BigDecimal("200.00"), "order-cancel-" + order,
                ActorType.SYSTEM, null));
        assertThat(retried.status().name()).isEqualTo("COMPLETED");
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(refundGateway.distinctRefunds()).isEqualTo(1);
    }

    @Test
    void rnf045_anUnconfirmedRefundStaysPendingWithoutRevertingAnything() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        refundGateway.setMode(SimulatedRefundGateway.Mode.PENDING);

        cancel(order).andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.refund.status").value("PENDING"));

        assertThat(statusOf(order)).isEqualTo("CANCELLED");
        assertThat(paymentOf(order)).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("PENDING");
    }

    @Test
    void anOrderWhosePaymentWasNotApprovedNeedsNoRefund() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        jdbc.update("UPDATE orders SET payment_status = 'REFUNDED' WHERE id = ?", order);

        cancel(order).andExpect(status().isOk()).andExpect(jsonPath("$.refund.status").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));

        assertThat(count("refunds")).isZero();
        assertThat(refundGateway.requestCount()).isZero();
        assertThat(statusOf(order)).isEqualTo("CANCELLED");
    }

    // ---------- RNF-043: idempotencia y concurrencia ----------

    @Test
    void rnf043_cancellingTwiceHasASingleEffect() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 2, "100.00");

        cancel(order).andExpect(status().isOk());
        cancel(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_INVALID_TRANSITION"));

        assertThat(stockOf(product)).isEqualTo(7); // repuesto una sola vez
        assertThat(count("order_cancellations")).isEqualTo(1);
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(refundGateway.distinctRefunds()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE to_status = 'CANCELLED'", Integer.class))
                .isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'ORDER_CANCELLED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rnf043_twoSimultaneousCancellationsProduceExactlyOneSuccessAndOne409() throws Exception {
        for (int round = 0; round < 3; round++) {
            long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
            int stockBefore = stockOf(product);
            var barrier = new CyclicBarrier(2);
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await();
                        return cancel(order).andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            List<Integer> statuses = calls.stream().map(CompletableFuture::join).sorted().toList();

            assertThat(statuses).as("ronda %d", round).containsExactly(200, 409);
            assertThat(stockOf(product)).isEqualTo(stockBefore + 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_cancellations WHERE order_id = ?", Integer.class, order))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE order_id = ?", Integer.class, order))
                    .isEqualTo(1);
        }
    }

    @Test
    void rnf043_aCancellationAndAPreparationRacingLeaveExactlyOneWinner() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        var barrier = new CyclicBarrier(2);
        CompletableFuture<Integer> cancelling = CompletableFuture.supplyAsync(() -> {
            try {
                barrier.await();
                return cancel(order).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        CompletableFuture<Integer> preparing = CompletableFuture.supplyAsync(() -> {
            try {
                barrier.await();
                return performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/start-preparation"))
                        .andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        int cancelStatus = cancelling.join();
        int prepareStatus = preparing.join();

        // O gana la preparación y luego la cancelación (desde IN_PREPARATION) o gana la cancelación y la preparación falla.
        if (prepareStatus == 200) {
            assertThat(cancelStatus).isIn(200, 409);
        } else {
            assertThat(prepareStatus).isEqualTo(409);
            assertThat(cancelStatus).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_cancellations", Integer.class)).isLessThanOrEqualTo(1);
        assertThat(stockOf(product)).isEqualTo(cancelStatus == 200 ? 6 : 5);
    }

    // ---------- D10: el caso de uso es genérico (CU-11 lo reutilizará con el comprador) ----------

    @Test
    void d10_theGenericUseCaseAlsoCancelsOnBehalfOfTheBuyerWithoutAStoreScope() {
        long order = seedOrder(1, "CONFIRMED", product, 2, "100.00");

        var result = cancelOrder.execute(new CancelOrderCommand(order, null, CancellationInitiator.BUYER, 77L,
                CancellationReason.OTHER, "Me arrepentí de la compra"));

        assertThat(result.order().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.refund().status()).isEqualTo(CancelOrderResult.RefundOutcome.Status.COMPLETED);
        assertThat(stockOf(product)).isEqualTo(7);
        assertThat(jdbc.queryForMap("SELECT initiator, cancelled_by_id FROM order_cancellations"))
                .containsEntry("initiator", "BUYER").containsEntry("cancelled_by_id", 77L);
        assertThat(jdbc.queryForMap("SELECT actor_type, actor_id FROM order_status_history WHERE to_status = 'CANCELLED'"))
                .containsEntry("actor_type", "BUYER").containsEntry("actor_id", 77L);
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE recipient_type = 'BUYER'", String.class))
                .doesNotContain("vendedor");
    }

    @Test
    void d10_aSellerCancellationWithoutAStoreIsRejectedAtConstructionTime() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CancelOrderCommand(1L, null,
                CancellationInitiator.SELLER, 1L, CancellationReason.OUT_OF_STOCK, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- RNF-003 ----------

    @Test
    void rnf003_anotherStoreCannotCancelTheOrder() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");

        performAsSeller(seller, 2, post("/api/seller/orders/" + order + "/cancel").contentType("application/json")
                .content("{\"reasonCode\":\"OUT_OF_STOCK\"}")).andExpect(status().isNotFound());
        perform(seller, post("/api/seller/orders/" + order + "/cancel").contentType("application/json")
                .content("{\"reasonCode\":\"OUT_OF_STOCK\"}")).andExpect(status().isUnauthorized());
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        performAsSeller(buyer, 1, post("/api/seller/orders/" + order + "/cancel").contentType("application/json")
                .content("{\"reasonCode\":\"OUT_OF_STOCK\"}")).andExpect(status().isForbidden());

        assertNoEffects(order, "CONFIRMED", 5);
    }
}
