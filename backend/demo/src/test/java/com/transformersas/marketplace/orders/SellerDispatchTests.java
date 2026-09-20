package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RF-114, RF-115, RF-120, RF-121, RF-123, A5, A6 y RNF-043/045: dejar el pedido listo para despacho y solicitar el
 * envío al servicio logístico (simulado).
 */
class SellerDispatchTests extends AbstractIntegrationTest {

    @Autowired SimulatedLogisticsGateway logistics;
    @Autowired SimulatedExternalNotificationGateway notices;

    private Long sellerAccountId;
    private Session seller;
    private long product;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM notifications");
        logistics.reset();
        notices.reset();
        sellerAccountId = createAccount("seller@example.com", "VENDEDOR");
        seller = login("seller@example.com");
        seedStore(2, "Otra tienda");
        product = seedProduct(1, "Lámpara", 7, "100.00");
    }

    private ResultActions ready(long order) throws Exception {
        return performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/ready-for-dispatch"));
    }

    private ResultActions shipment(long order) throws Exception {
        return performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/shipment"));
    }

    private String statusOf(long order) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order);
    }

    private int shipments() {
        return count("shipments");
    }

    // ---------- Flujo principal completo (pasos 1 a 17) ----------

    @Test
    void mainFlow_steps1to17_fromAConfirmedPurchaseToReadyForDispatchWithLogisticsReference() throws Exception {
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        long order = checkoutOrder(buyer, product, 2); // CU-03: pedido creado por una compra aprobada
        assertThat(count("notifications")).isZero();

        // 1-3: el vendedor accede a sus pedidos, ve el confirmado y puede filtrar.
        performAsSeller(seller, 1, get("/api/seller/orders")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].id").value(order))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));
        performAsSeller(seller, 1, get("/api/seller/orders?status=CONFIRMED&from=2000-01-01&orderId=" + order))
                .andExpect(jsonPath("$.content", hasSize(1)));
        // 4-5: detalle con productos, cantidades, pago, inventario, dirección histórica y método de envío.
        performAsSeller(seller, 1, get("/api/seller/orders/" + order)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2)).andExpect(jsonPath("$.paymentStatus").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].currentStock").value(5))
                .andExpect(jsonPath("$.delivery.city").value("Bogotá")).andExpect(jsonPath("$.shippingMethod").value("STANDARD"));
        // 6-8: confirma la preparación; estado En preparación y notificación al comprador.
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/start-preparation"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PREPARATION"));
        // 9-11: (prepara los productos) confirma que está preparado; estado Listo para despacho.
        // 12-16: usa la entrega guardada, solicita el envío, guarda la referencia y notifica al comprador.
        ready(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_FOR_DISPATCH"))
                .andExpect(jsonPath("$.shipment.status").value("CREATED"))
                .andExpect(jsonPath("$.shipment.shipmentId").value("SIM-order-" + order))
                .andExpect(jsonPath("$.shipment.trackingCode").value("TRK-" + order));

        // 15 y 17: la referencia queda guardada y el pedido permanece Listo para despacho (nunca Recogido).
        assertThat(statusOf(order)).isEqualTo("READY_FOR_DISPATCH");
        assertThat(jdbc.queryForMap("SELECT * FROM shipments")).containsEntry("order_id", order)
                .containsEntry("provider_shipment_id", "SIM-order-" + order).containsEntry("status", "CREATED");
        performAsSeller(seller, 1, get("/api/seller/orders/" + order)).andExpect(jsonPath("$.status").value("READY_FOR_DISPATCH"))
                .andExpect(jsonPath("$.shipment.trackingCode").value("TRK-" + order)) // RF-115: consultable después
                .andExpect(jsonPath("$.history", hasSize(3)));
        assertThat(jdbc.queryForList("SELECT to_status FROM order_status_history WHERE order_id = ? ORDER BY id",
                String.class, order)).containsExactly("CONFIRMED", "IN_PREPARATION", "READY_FOR_DISPATCH");
        // RF-123: una notificación por cambio relevante, asociada al pedido correcto, y aviso externo de cada una.
        assertThat(jdbc.queryForList("SELECT event_key FROM notifications ORDER BY id", String.class))
                .containsExactly("order-" + order + "-IN_PREPARATION", "order-" + order + "-READY_FOR_DISPATCH");
        await().atMost(Duration.ofSeconds(10)).until(() -> notices.accepted().size() == 2);
        // RNF-009: auditoría de las dos transiciones y del resultado logístico.
        assertThat(jdbc.queryForList("SELECT action FROM audit_events ORDER BY id", String.class))
                .containsExactly("ORDER_STATUS_CHANGED", "ORDER_STATUS_CHANGED", "SHIPMENT_REQUEST");
    }

    // ---------- RF-120 ----------

    @ParameterizedTest(name = "estado {0}")
    @EnumSource(value = OrderStatus.class, names = "IN_PREPARATION", mode = EnumSource.Mode.EXCLUDE)
    void rf120_onlyAnOrderInPreparationCanBecomeReadyForDispatch(OrderStatus current) throws Exception {
        long order = seedOrder(1, current.name(), product, 1, "100.00");

        ready(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_INVALID_TRANSITION"));

        assertThat(statusOf(order)).isEqualTo(current.name());
        assertThat(count("order_status_history")).isEqualTo(1);
        assertThat(count("notifications")).isZero();
        assertThat(logistics.requestCount()).isZero();
        assertThat(shipments()).isZero();
    }

    @Test
    void rf120_theChangeIsRecordedOnceEvenIfTheActionIsSubmittedTwice() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");

        ready(order).andExpect(status().isOk());
        ready(order).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE to_status = 'READY_FOR_DISPATCH'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_key = ?", Integer.class,
                "order-" + order + "-READY_FOR_DISPATCH")).isEqualTo(1);
        assertThat(shipments()).isEqualTo(1);
        assertThat(logistics.requestCount()).isEqualTo(1);
    }

    @Test
    void rf120_readyOrderHasHistoryWithDateAndResponsibleAndAnAuditedTransition() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");

        ready(order).andExpect(status().isOk());

        Map<String, Object> history = jdbc.queryForMap(
                "SELECT * FROM order_status_history WHERE to_status = 'READY_FOR_DISPATCH'");
        assertThat(history).containsEntry("from_status", "IN_PREPARATION").containsEntry("actor_type", "SELLER")
                .containsEntry("actor_id", sellerAccountId);
        assertThat(history.get("created_at")).isNotNull();
        assertThat(jdbc.queryForMap("SELECT * FROM audit_events WHERE action = 'ORDER_STATUS_CHANGED'"))
                .containsEntry("actor_id", sellerAccountId).containsEntry("outcome", "SUCCESS");
    }

    // ---------- RF-121 / A3: las novedades abiertas impiden el despacho ----------

    @Test
    void rf121_anOpenIssueBlocksReadyForDispatchUntilItIsResolved() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/issues").contentType("application/json")
                .content("{\"type\":\"DAMAGED_PRODUCT\",\"description\":\"Lámpara rota\"}")).andExpect(status().isCreated());
        long issue = jdbc.queryForObject("SELECT id FROM order_issues", Long.class);

        ready(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPEN_ISSUES"))
                .andExpect(jsonPath("$.details", hasSize(1))).andExpect(jsonPath("$.details[0].id").value(issue))
                .andExpect(jsonPath("$.details[0].type").value("DAMAGED_PRODUCT"))
                .andExpect(jsonPath("$.details[0].description").value("Lámpara rota"));
        assertThat(statusOf(order)).isEqualTo("IN_PREPARATION");
        assertThat(logistics.requestCount()).isZero();

        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/issues/" + issue + "/resolve"))
                .andExpect(status().isOk());
        ready(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_FOR_DISPATCH"));
    }

    @Test
    void a3_theOrderNeverReachesReadyWhileTheInventoryInconsistencyIsOpen() throws Exception {
        long order = seedOrder(1, "CONFIRMED", product, 1, "100.00");
        jdbc.update("DELETE FROM products WHERE id = ?", product);
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/start-preparation"))
                .andExpect(status().isConflict()); // registra la novedad automática

        ready(order).andExpect(status().isConflict()); // sigue en CONFIRMED: transición inválida

        assertThat(statusOf(order)).isEqualTo("CONFIRMED");
        assertThat(shipments()).isZero();
    }

    // ---------- RF-114/115 ----------

    @Test
    void rf114_rf115_theShipmentReferenceIsStoredAndTheOrderStaysReadyForDispatch() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");

        MvcResult result = ready(order).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("SIM-order-" + order);
        assertThat(statusOf(order)).isEqualTo("READY_FOR_DISPATCH").isNotEqualTo("PICKED_UP");
        assertThat(shipments()).isEqualTo(1);
        assertThat(logistics.distinctShipments()).isEqualTo(1);
    }

    // ---------- A5: fallo al crear el envío ----------

    @Test
    void a5_rnf045_aProviderFailureKeepsTheOrderReadyReportsTheFailureAndAllowsASafeRetry() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        logistics.setMode(SimulatedLogisticsGateway.Mode.UNAVAILABLE);

        ready(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_FOR_DISPATCH"))
                .andExpect(jsonPath("$.shipment.status").value("FAILED"))
                .andExpect(jsonPath("$.shipment.message").value(org.hamcrest.Matchers.containsString("reintentar")))
                .andExpect(jsonPath("$.shipment.shipmentId").doesNotExist());

        assertThat(statusOf(order)).isEqualTo("READY_FOR_DISPATCH"); // el fallo no revierte nada
        assertThat(shipments()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_key = ?", Integer.class,
                "order-" + order + "-READY_FOR_DISPATCH")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT outcome FROM audit_events WHERE action = 'SHIPMENT_REQUEST'", String.class)).isEqualTo("FAILURE");

        // Reintento mientras el proveedor sigue caído: 502 controlado y el pedido sigue listo.
        shipment(order).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("SHIPMENT_PROVIDER_FAILED"));
        assertThat(statusOf(order)).isEqualTo("READY_FOR_DISPATCH");

        // El proveedor se recupera: el reintento crea el envío una sola vez.
        logistics.setMode(SimulatedLogisticsGateway.Mode.OK);
        shipment(order).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.shipmentId").value("SIM-order-" + order));
        shipment(order).andExpect(status().isOk()).andExpect(jsonPath("$.shipmentId").value("SIM-order-" + order));

        assertThat(shipments()).isEqualTo(1);
        assertThat(logistics.distinctShipments()).isEqualTo(1);
    }

    @Test
    void a5_aDefinitiveRejectionIsReportedWithoutBreakingTheOrder() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        logistics.setMode(SimulatedLogisticsGateway.Mode.REJECT);

        ready(order).andExpect(status().isOk()).andExpect(jsonPath("$.shipment.status").value("FAILED"))
                .andExpect(jsonPath("$.shipment.message").value(org.hamcrest.Matchers.containsString("rechazó")));
        shipment(order).andExpect(status().isBadGateway());

        assertThat(statusOf(order)).isEqualTo("READY_FOR_DISPATCH");
        assertThat(shipments()).isZero();
    }

    // ---------- A6: solicitud repetida ----------

    @Test
    void a6_rnf043_aRepeatedShipmentRequestReusesTheExistingReferenceWithoutCallingTheProvider() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        ready(order).andExpect(status().isOk());

        shipment(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.shipmentId").value("SIM-order-" + order))
                .andExpect(jsonPath("$.trackingCode").value("TRK-" + order));
        shipment(order).andExpect(status().isOk());

        assertThat(shipments()).isEqualTo(1);
        assertThat(logistics.requestCount()).isEqualTo(1); // solo la solicitud original llegó al proveedor
    }

    @ParameterizedTest(name = "estado {0}")
    @EnumSource(value = OrderStatus.class, names = "READY_FOR_DISPATCH", mode = EnumSource.Mode.EXCLUDE)
    void a6_theShipmentIsOnlyRequestedForReadyOrders(OrderStatus current) throws Exception {
        long order = seedOrder(1, current.name(), product, 1, "100.00");

        shipment(order).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_READY_FOR_DISPATCH"));

        assertThat(logistics.requestCount()).isZero();
        assertThat(shipments()).isZero();
    }

    @Test
    void rnf043_twoSimultaneousShipmentRetriesProduceASingleShipment() throws Exception {
        long order = seedOrder(1, "READY_FOR_DISPATCH", product, 1, "100.00");
        var barrier = new CyclicBarrier(2);
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    barrier.await();
                    return shipment(order).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).sorted().toList();

        assertThat(statuses).containsExactly(200, 201); // una creó el envío y la otra reutilizó la referencia
        assertThat(shipments()).isEqualTo(1);
        assertThat(logistics.distinctShipments()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'SHIPMENT_REQUEST' AND outcome = 'SUCCESS'",
                Integer.class)).isEqualTo(1);
    }

    // ---------- A4 y RNF-003 ----------

    @Test
    void a4_theReadyRequestRejectsDeliveryFieldsAndChangesNothing() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");

        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/ready-for-dispatch")
                .contentType("application/json").content("{\"street\":\"Otra\",\"shippingMethod\":\"EXPRESS\"}"))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(order)).isEqualTo("IN_PREPARATION");
        assertThat(logistics.requestCount()).isZero();
    }

    @Test
    void rnf003_anotherStoreCannotMarkReadyOrRequestTheShipment() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");

        performAsSeller(seller, 2, post("/api/seller/orders/" + order + "/ready-for-dispatch"))
                .andExpect(status().isNotFound());
        performAsSeller(seller, 2, post("/api/seller/orders/" + order + "/shipment")).andExpect(status().isNotFound());
        perform(seller, post("/api/seller/orders/" + order + "/ready-for-dispatch")).andExpect(status().isUnauthorized());
        Session buyer = sessionWithRole("buyer@example.com", "COMPRADOR");
        performAsSeller(buyer, 1, post("/api/seller/orders/" + order + "/ready-for-dispatch"))
                .andExpect(status().isForbidden());

        assertThat(statusOf(order)).isEqualTo("IN_PREPARATION");
        assertThat(logistics.requestCount()).isZero();
        assertThat(count("audit_events")).isZero();
    }

    @Test
    void rnf043_simultaneousReadyRequestsProduceExactlyOneSuccessAndOneShipment() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        var barrier = new CyclicBarrier(2);
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    barrier.await();
                    return ready(order).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).sorted().toList();

        assertThat(statuses).containsExactly(200, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE to_status = 'READY_FOR_DISPATCH'",
                Integer.class)).isEqualTo(1);
        assertThat(shipments()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_key = ?", Integer.class,
                "order-" + order + "-READY_FOR_DISPATCH")).isEqualTo(1);
        performAsSeller(seller, 1, get("/api/seller/orders/" + order)).andExpect(jsonPath("$.status").value("READY_FOR_DISPATCH"))
                .andExpect(jsonPath("$.status").value(not("PICKED_UP")));
    }
}
