package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.support.AbstractTrackingTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-24 por webhook (RF-116 a RF-119, A1 a A9, RNF-043, RNF-046): el servicio logístico informa Recogido, En camino,
 * novedades, intentos fallidos, Entregado y Retornado al vendedor; el marketplace registra cada una una sola vez.
 */
class ShipmentTrackingWebhookTests extends AbstractTrackingTest {

    private Long buyerId;
    private Session buyer;
    private Session seller;
    private long order;

    @BeforeEach
    void setUp() throws Exception {
        buyerId = createAccount("buyer@example.com", "COMPRADOR");
        buyer = login("buyer@example.com");
        seller = sellerOfStore("seller@example.com", 1);
        order = shippedOrder(buyerId, "READY_FOR_DISPATCH");
    }

    private void expectResult(ResultActions actions, String result) throws Exception {
        actions.andExpect(status().isOk()).andExpect(jsonPath("$.result").value(result));
    }

    private ResultActions buyerTracking() throws Exception {
        return perform(buyer, get("/api/orders/" + order + "/tracking"));
    }

    private ResultActions sellerTracking() throws Exception {
        return performAsSeller(seller, 1, get("/api/seller/orders/" + order + "/tracking"));
    }

    // ---------- Flujo principal (pasos 1 a 15) ----------

    @Test
    void mainFlow_pickedUpInTransitDelivered_recordsEachOnceNotifiesAndKeepsTheEvidence() throws Exception {
        // Pasos 3 a 6: logística confirma que recogió el paquete.
        expectResult(sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(30)), "APPLIED");
        assertThat(orderStatus(order)).isEqualTo("PICKED_UP");
        // Pasos 7 a 9: el paquete va en camino.
        Map<String, Object> transit = shipmentEvent(order, "evt-2", "IN_TRANSIT", secondsAgo(20));
        transit.put("location", "Centro de distribución Bogotá");
        expectResult(signedPost(SHIPMENTS_WEBHOOK, transit), "APPLIED");
        assertThat(orderStatus(order)).isEqualTo("IN_TRANSIT");
        // Pasos 11 a 14: entrega con evidencia.
        Map<String, Object> delivered = shipmentEvent(order, "evt-3", "DELIVERED", secondsAgo(10));
        delivered.put("description", "Entregado en portería");
        delivered.put("evidence", Map.of("type", "SIGNATURE", "reference", "POD-77"));
        expectResult(signedPost(SHIPMENTS_WEBHOOK, delivered), "APPLIED");
        assertThat(orderStatus(order)).isEqualTo("DELIVERED");

        // Postcondición 2: el historial conserva cada actualización con su origen, fecha y hora.
        assertThat(historyStatuses(order)).containsExactly("READY_FOR_DISPATCH", "PICKED_UP", "IN_TRANSIT", "DELIVERED");
        assertThat(jdbc.queryForList("SELECT DISTINCT actor_type FROM order_status_history WHERE from_status IS NOT NULL",
                String.class)).containsExactly("LOGISTICS");
        assertThat(orderEventOutcomes(order)).containsExactly("PICKED_UP:APPLIED", "IN_TRANSIT:APPLIED",
                "DELIVERED:APPLIED");
        assertThat(jdbc.queryForObject("SELECT tracking_active FROM shipments WHERE order_id = ?", Boolean.class, order))
                .isFalse();
        // Postcondición 5 (RF-119): una notificación por cambio para el comprador y la tienda solo en la entrega.
        assertThat(notificationKeys()).containsExactly("order-" + order + "-PICKED_UP-evt-1",
                "order-" + order + "-IN_TRANSIT-evt-2", "order-" + order + "-DELIVERED-evt-3",
                "order-" + order + "-DELIVERED-evt-3-STORE");
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications WHERE event_key NOT LIKE '%STORE'",
                Long.class)).containsOnly(buyerId);
        await().atMost(Duration.ofSeconds(10)).until(() -> notices.accepted().size() == 4);
        // RNF-009: el origen (LOGISTICS), la acción y el resultado de cada transición quedan auditados.
        assertThat(jdbc.queryForList("SELECT CONCAT(actor_type, ':', action, ':', outcome, ':', entity_id) FROM audit_events "
                + "ORDER BY id", String.class)).containsExactly(
                "LOGISTICS:ORDER_STATUS_CHANGED:SUCCESS:" + order, "LOGISTICS:ORDER_STATUS_CHANGED:SUCCESS:" + order,
                "LOGISTICS:ORDER_STATUS_CHANGED:SUCCESS:" + order);

        // Postcondiciones 4 y 6, paso 15: comprador y vendedor consultan el mismo historial, con la evidencia.
        for (ResultActions view : List.of(buyerTracking(), sellerTracking())) {
            view.andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(order))
                    .andExpect(jsonPath("$.status").value("DELIVERED")).andExpect(jsonPath("$.trackingCode").value("TRK-" + order))
                    .andExpect(jsonPath("$.tracking").value(false)).andExpect(jsonPath("$.events", hasSize(3)))
                    .andExpect(jsonPath("$.events[0].type").value("PICKED_UP"))
                    .andExpect(jsonPath("$.events[1].location").value("Centro de distribución Bogotá"))
                    .andExpect(jsonPath("$.events[2].type").value("DELIVERED"))
                    .andExpect(jsonPath("$.events[2].source").value("WEBHOOK"))
                    .andExpect(jsonPath("$.deliveryEvidence.reference").value("POD-77"))
                    .andExpect(jsonPath("$.deliveredAt").isNotEmpty());
        }
    }

    // ---------- A5: evento repetido ----------

    @Test
    void a5_repeatedEventDoesNotChangeStateNorNotifyTwice() throws Exception {
        expectResult(sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(30)), "APPLIED");
        expectResult(sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(30)), "DUPLICATE");
        expectResult(sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(30)), "DUPLICATE");

        assertThat(orderEventOutcomes(order)).containsExactly("PICKED_UP:APPLIED");
        assertThat(historyStatuses(order)).containsExactly("READY_FOR_DISPATCH", "PICKED_UP");
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void rnf043_theSameEventSentConcurrentlyHasASingleEffect() throws Exception {
        int senders = 8;
        var barrier = new CyclicBarrier(senders);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < senders; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    barrier.await();
                    MvcResult result = sendShipmentEvent(order, "evt-race", "PICKED_UP", secondsAgo(5)).andReturn();
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    return json.readTree(result.getResponse().getContentAsString()).get("result").asString();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }));
        }
        List<String> results = futures.stream().map(CompletableFuture::join).toList();

        assertThat(results).containsOnlyOnce("APPLIED").filteredOn("DUPLICATE"::equals).hasSize(senders - 1);
        assertThat(orderEventOutcomes(order)).containsExactly("PICKED_UP:APPLIED");
        assertThat(historyStatuses(order)).containsExactly("READY_FOR_DISPATCH", "PICKED_UP");
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void differentEventsSentConcurrentlyEndInTheMostAdvancedStateWithBothRecorded() throws Exception {
        var barrier = new CyclicBarrier(2);
        CompletableFuture<Void> pickedUp = CompletableFuture.runAsync(() -> concurrent(barrier, "evt-a", "PICKED_UP", 20));
        CompletableFuture<Void> inTransit = CompletableFuture.runAsync(() -> concurrent(barrier, "evt-b", "IN_TRANSIT", 10));
        CompletableFuture.allOf(pickedUp, inTransit).join();

        assertThat(orderStatus(order)).isEqualTo("IN_TRANSIT");
        assertThat(jdbc.queryForList("SELECT event_type FROM shipment_tracking_events WHERE order_id = ?", String.class,
                order)).containsExactlyInAnyOrder("PICKED_UP", "IN_TRANSIT");
        // Sin importar el orden de llegada nunca se retrocede: o se aplicó PICKED_UP y luego IN_TRANSIT, o al revés.
        assertThat(historyStatuses(order).get(historyStatuses(order).size() - 1)).isEqualTo("IN_TRANSIT");
    }

    private void concurrent(CyclicBarrier barrier, String eventId, String type, long secondsAgo) {
        try {
            barrier.await();
            sendShipmentEvent(order, eventId, type, secondsAgo(secondsAgo)).andExpect(status().isOk());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ---------- A6: actualización fuera de orden ----------

    @Test
    void a6_outOfOrderUpdateNeverMovesTheStateBackButIsKeptForTraceability() throws Exception {
        sendShipmentEvent(order, "evt-2", "IN_TRANSIT", secondsAgo(20)).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("IN_TRANSIT");

        // La recogida llega tarde: ya estamos en camino, no se retrocede.
        expectResult(sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(30)), "OUT_OF_ORDER");

        assertThat(orderStatus(order)).isEqualTo("IN_TRANSIT");
        assertThat(orderEventOutcomes(order)).containsExactly("IN_TRANSIT:APPLIED", "PICKED_UP:OUT_OF_ORDER");
        assertThat(historyStatuses(order)).containsExactly("READY_FOR_DISPATCH", "IN_TRANSIT");
        assertThat(count("notifications")).isEqualTo(1);
        // La línea de tiempo la muestra en orden cronológico, con la actualización conservada.
        buyerTracking().andExpect(jsonPath("$.events[0].type").value("PICKED_UP"))
                .andExpect(jsonPath("$.events[0].outcome").value("OUT_OF_ORDER"))
                .andExpect(jsonPath("$.events[1].type").value("IN_TRANSIT"));
    }

    @Test
    void a6_nothingAdvancesAFinalState() throws Exception {
        sendShipmentEvent(order, "evt-1", "IN_TRANSIT", secondsAgo(30)).andExpect(status().isOk());
        sendShipmentEvent(order, "evt-2", "DELIVERED", secondsAgo(20)).andExpect(status().isOk());

        expectResult(sendShipmentEvent(order, "evt-3", "IN_TRANSIT", secondsAgo(10)), "OUT_OF_ORDER");
        expectResult(sendShipmentEvent(order, "evt-4", "RETURNED_TO_SELLER", secondsAgo(5)), "OUT_OF_ORDER");
        expectResult(sendShipmentEvent(order, "evt-5", "NEXT_ATTEMPT_SCHEDULED", secondsAgo(4)), "OUT_OF_ORDER");

        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
    }

    @Test
    void anUpdateForAnOrderThatIsNotShippedYetIsKeptButNotApplied() throws Exception {
        jdbc.update("UPDATE orders SET status = 'IN_PREPARATION' WHERE id = ?", order);

        expectResult(sendShipmentEvent(order, "evt-1", "DELIVERED", secondsAgo(5)), "OUT_OF_ORDER");

        assertThat(orderStatus(order)).isEqualTo("IN_PREPARATION");
        assertThat(jdbc.queryForObject("SELECT tracking_active FROM shipments WHERE order_id = ?", Boolean.class, order))
                .isTrue();
    }

    // ---------- Información adicional del mismo estado (pasos 8 y 10) ----------

    @Test
    void repeatedInTransitWithAnotherEventIdOnlyAddsTrackingInformation() throws Exception {
        sendShipmentEvent(order, "evt-1", "IN_TRANSIT", secondsAgo(30)).andExpect(status().isOk());
        Map<String, Object> update = shipmentEvent(order, "evt-2", "IN_TRANSIT", secondsAgo(20));
        update.put("location", "Medellín");

        expectResult(signedPost(SHIPMENTS_WEBHOOK, update), "RECORDED");

        assertThat(historyStatuses(order)).containsExactly("READY_FOR_DISPATCH", "IN_TRANSIT");
        assertThat(count("notifications")).isEqualTo(1);
        buyerTracking().andExpect(jsonPath("$.events", hasSize(2))).andExpect(jsonPath("$.events[1].location").value("Medellín"));
    }

    // ---------- A1 a A4: novedades, intentos fallidos, nuevo intento y retorno ----------

    @Test
    void a1_deliveryExceptionIsShownNotifiedAndDoesNotEraseThePreviousStates() throws Exception {
        sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(40)).andExpect(status().isOk());
        sendShipmentEvent(order, "evt-2", "IN_TRANSIT", secondsAgo(30)).andExpect(status().isOk());
        Map<String, Object> exception = shipmentEvent(order, "evt-3", "DELIVERY_EXCEPTION", secondsAgo(20));
        exception.put("description", "Vía bloqueada");
        exception.put("location", "Bogotá");

        expectResult(signedPost(SHIPMENTS_WEBHOOK, exception), "APPLIED");

        assertThat(orderStatus(order)).isEqualTo("DELIVERY_EXCEPTION");
        assertThat(historyStatuses(order)).containsExactly("READY_FOR_DISPATCH", "PICKED_UP", "IN_TRANSIT",
                "DELIVERY_EXCEPTION");
        assertThat(jdbc.queryForObject("SELECT reason FROM order_status_history WHERE to_status = 'DELIVERY_EXCEPTION'",
                String.class)).isEqualTo("Actualización logística: Vía bloqueada");
        assertThat(notificationKeys()).contains("order-" + order + "-DELIVERY_EXCEPTION-evt-3",
                "order-" + order + "-DELIVERY_EXCEPTION-evt-3-STORE");
        sellerTracking().andExpect(jsonPath("$.status").value("DELIVERY_EXCEPTION"))
                .andExpect(jsonPath("$.events", hasSize(3))).andExpect(jsonPath("$.events[2].description").value("Vía bloqueada"));
    }

    @Test
    void a1_theTrackingContinuesAfterTheExceptionAndASecondOneIsNotifiedToo() throws Exception {
        sendShipmentEvent(order, "evt-1", "IN_TRANSIT", secondsAgo(50)).andExpect(status().isOk());
        sendShipmentEvent(order, "evt-2", "DELIVERY_EXCEPTION", secondsAgo(40)).andExpect(status().isOk());
        expectResult(sendShipmentEvent(order, "evt-3", "DELIVERY_EXCEPTION", secondsAgo(30)), "APPLIED");
        expectResult(sendShipmentEvent(order, "evt-4", "IN_TRANSIT", secondsAgo(20)), "APPLIED");
        expectResult(sendShipmentEvent(order, "evt-5", "DELIVERED", secondsAgo(10)), "APPLIED");

        assertThat(historyStatuses(order)).containsExactly("READY_FOR_DISPATCH", "IN_TRANSIT", "DELIVERY_EXCEPTION",
                "DELIVERY_EXCEPTION", "IN_TRANSIT", "DELIVERED");
        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
    }

    @Test
    void a2_a3_failedAttemptKeepsReasonAndDateAndAnotherAttemptContinuesWithoutManualChanges() throws Exception {
        sendShipmentEvent(order, "evt-1", "IN_TRANSIT", secondsAgo(50)).andExpect(status().isOk());
        Map<String, Object> failed = shipmentEvent(order, "evt-2", "DELIVERY_ATTEMPT_FAILED", secondsAgo(40));
        failed.put("description", "Nadie en casa");
        expectResult(signedPost(SHIPMENTS_WEBHOOK, failed), "APPLIED");
        assertThat(orderStatus(order)).isEqualTo("DELIVERY_ATTEMPT_FAILED");
        assertThat(jdbc.queryForObject("SELECT description FROM shipment_tracking_events WHERE provider_event_id = 'evt-2'",
                String.class)).isEqualTo("Nadie en casa");

        // A3: logística programa otro intento; el estado no cambia hasta que ella informe algo nuevo.
        expectResult(sendShipmentEvent(order, "evt-3", "NEXT_ATTEMPT_SCHEDULED", secondsAgo(30)), "RECORDED");
        assertThat(orderStatus(order)).isEqualTo("DELIVERY_ATTEMPT_FAILED");
        expectResult(sendShipmentEvent(order, "evt-4", "IN_TRANSIT", secondsAgo(20)), "APPLIED");
        expectResult(sendShipmentEvent(order, "evt-5", "DELIVERED", secondsAgo(10)), "APPLIED");

        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
        assertThat(notificationKeys()).contains("order-" + order + "-DELIVERY_ATTEMPT_FAILED-evt-2");
        assertThat(orderEventOutcomes(order)).contains("NEXT_ATTEMPT_SCHEDULED:RECORDED");
    }

    @Test
    void a4_returnToSellerClosesTheTrackingAndIsAuditedOnTheRightOrder() throws Exception {
        long other = shippedOrder(buyerId, "READY_FOR_DISPATCH");
        sendShipmentEvent(order, "evt-1", "IN_TRANSIT", secondsAgo(50)).andExpect(status().isOk());
        sendShipmentEvent(order, "evt-2", "DELIVERY_ATTEMPT_FAILED", secondsAgo(40)).andExpect(status().isOk());

        expectResult(sendShipmentEvent(order, "evt-3", "RETURNED_TO_SELLER", secondsAgo(30)), "APPLIED");

        assertThat(orderStatus(order)).isEqualTo("RETURNED_TO_SELLER");
        assertThat(orderStatus(other)).isEqualTo("READY_FOR_DISPATCH");
        assertThat(jdbc.queryForObject("SELECT tracking_active FROM shipments WHERE order_id = ?", Boolean.class, order))
                .isFalse();
        assertThat(jdbc.queryForList("SELECT entity_id FROM audit_events WHERE action = 'ORDER_STATUS_CHANGED' "
                + "AND details LIKE '%RETURNED_TO_SELLER%'", String.class)).containsExactly(String.valueOf(order));
        assertThat(notificationKeys()).contains("order-" + order + "-RETURNED_TO_SELLER-evt-3",
                "order-" + order + "-RETURNED_TO_SELLER-evt-3-STORE");
        // RF-117: el seguimiento mostrado corresponde al pedido seleccionado y no mezcla otros pedidos.
        perform(buyer, get("/api/orders/" + other + "/tracking")).andExpect(jsonPath("$.events", hasSize(0)));
    }

    // ---------- A9: fallo del aviso externo ----------

    @Test
    void a9_externalNoticeFailureKeepsTheUpdateVisibleAndTheInternalNotification() throws Exception {
        notices.setMode(com.transformersas.marketplace.notifications.infrastructure.gateway
                .SimulatedExternalNotificationGateway.Mode.UNAVAILABLE);

        expectResult(sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(5)), "APPLIED");

        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT external_status FROM notifications", String.class).equals("FAILED"));
        assertThat(count("notifications")).isEqualTo(1);
        buyerTracking().andExpect(jsonPath("$.status").value("PICKED_UP")).andExpect(jsonPath("$.events", hasSize(1)));
    }

    // ---------- Identificación del envío (pasos 2 y 3) ----------

    @Test
    void anUpdateWithAnotherTrackingCodeIsRejectedAndAudited() throws Exception {
        Map<String, Object> wrong = shipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(5));
        wrong.put("trackingCode", "TRK-999");

        signedPost(SHIPMENTS_WEBHOOK, wrong).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_MISMATCH"));

        assertThat(orderStatus(order)).isEqualTo("READY_FOR_DISPATCH");
        assertThat(count("shipment_tracking_events")).isZero();
        assertThat(jdbc.queryForMap("SELECT action, outcome, actor_type FROM audit_events"))
                .containsEntry("action", "TRACKING_UPDATE_REJECTED").containsEntry("outcome", "FAILURE")
                .containsEntry("actor_type", "LOGISTICS");
    }

    @Test
    void anUpdateForAnUnknownShipmentIsNotFound() throws Exception {
        Map<String, Object> unknown = shipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(5));
        unknown.put("shipmentId", "SIM-order-424242");

        signedPost(SHIPMENTS_WEBHOOK, unknown).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_FOUND"));
    }

    // ---------- Autenticación y forma del webhook (RNF-003) ----------

    @Test
    void webhookRequiresAValidSignatureAndNoSessionOrCsrfToken() throws Exception {
        byte[] body = json.writeValueAsBytes(shipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(5)));

        mvc.perform(post(SHIPMENTS_WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        mvc.perform(post(SHIPMENTS_WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Logistics-Signature", "sha256=" + "0".repeat(64)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(SHIPMENTS_WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Logistics-Signature", "sha256=zz"))
                .andExpect(status().isUnauthorized());
        // La firma es del cuerpo exacto: alterarlo la invalida.
        mvc.perform(post(SHIPMENTS_WEBHOOK).contentType(MediaType.APPLICATION_JSON)
                        .content(new String(body).replace("PICKED_UP", "DELIVERED"))
                        .header("X-Logistics-Signature", signer.signature(body)))
                .andExpect(status().isUnauthorized());
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_DISPATCH");
        assertThat(count("shipment_tracking_events")).isZero();

        // Con la firma correcta pasa sin cookie de sesión ni CSRF.
        mvc.perform(post(SHIPMENTS_WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Logistics-Signature", signer.signature(body)))
                .andExpect(status().isOk());
    }

    @Test
    void invalidPayloadsAreBadRequestsAndUnknownTypesAreIgnored() throws Exception {
        Map<String, Object> noEvent = shipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(5));
        noEvent.remove("eventId");
        signedPost(SHIPMENTS_WEBHOOK, noEvent).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_PAYLOAD"));
        Map<String, Object> badDate = shipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(5));
        badDate.put("occurredAt", "ayer");
        signedPost(SHIPMENTS_WEBHOOK, badDate).andExpect(status().isBadRequest());
        Map<String, Object> noType = shipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(5));
        noType.remove("type");
        signedPost(SHIPMENTS_WEBHOOK, noType).andExpect(status().isBadRequest());
        Map<String, Object> badEventId = shipmentEvent(order, "evt 1 con espacios", "PICKED_UP", secondsAgo(5));
        signedPost(SHIPMENTS_WEBHOOK, badEventId).andExpect(status().isBadRequest());
        byte[] notJson = "no es json".getBytes();
        mvc.perform(post(SHIPMENTS_WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(notJson)
                        .header("X-Logistics-Signature", signer.signature(notJson)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_PAYLOAD"));
        byte[] literalNull = "null".getBytes();
        mvc.perform(post(SHIPMENTS_WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(literalNull)
                        .header("X-Logistics-Signature", signer.signature(literalNull)))
                .andExpect(status().isBadRequest());

        // Un tipo que el marketplace no conoce no se reintenta eternamente: se ignora sin efecto.
        expectResult(sendShipmentEvent(order, "evt-9", "TELEPORTED", secondsAgo(5)), "IGNORED");
        assertThat(count("shipment_tracking_events")).isZero();
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_DISPATCH");
    }

    @Test
    void theProviderMayAddFieldsWithoutBreakingTheIntegration() throws Exception {
        Map<String, Object> body = shipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(5));
        body.put("carrierNote", Map.of("x", 1));

        expectResult(signedPost(SHIPMENTS_WEBHOOK, body), "APPLIED");
    }

    @Test
    void overlongDescriptionsAreTruncatedInsteadOfFailing() throws Exception {
        Map<String, Object> body = shipmentEvent(order, "evt-1", "DELIVERY_EXCEPTION", secondsAgo(5));
        body.put("description", "x".repeat(900));
        body.put("location", " ".repeat(5));
        sendShipmentEvent(order, "evt-0", "IN_TRANSIT", secondsAgo(10)).andExpect(status().isOk());

        expectResult(signedPost(SHIPMENTS_WEBHOOK, body), "APPLIED");

        JsonNode event = json.readTree(buyerTracking().andReturn().getResponse().getContentAsString()).get("events").get(1);
        assertThat(event.get("description").asString()).hasSize(500);
        assertThat(event.path("location").isNull() || event.path("location").isMissingNode()).isTrue();
    }

    // ---------- A8: modificación manual ----------

    @Test
    void a8_neitherBuyerNorSellerCanChangeTheLogisticsStatesByHand() throws Exception {
        sendShipmentEvent(order, "evt-1", "IN_TRANSIT", secondsAgo(5)).andExpect(status().isOk());

        // El vendedor no puede volver a preparar ni cancelar un pedido que ya está en manos de logística.
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/start-preparation"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_INVALID_TRANSITION"));
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"OUT_OF_STOCK\"}"))
                .andExpect(status().isConflict());
        // No existe ningún endpoint para fijar un estado, y «actualizar» no admite un estado en el cuerpo.
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DELIVERED\"}")).andExpect(status().is4xxClientError());
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/tracking/refresh")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isBadRequest());
        perform(buyer, post("/api/orders/" + order + "/tracking/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DELIVERED\"}")).andExpect(status().isBadRequest());
        perform(buyer, post("/api/orders/" + order + "/cancellation")).andExpect(status().is4xxClientError());

        assertThat(orderStatus(order)).isEqualTo("IN_TRANSIT");
    }

    // ---------- RNF-038: correlación ----------

    @Test
    void rnf038_theCorrelationIdOfTheProviderRequestIsInEveryRelatedRecord() throws Exception {
        byte[] body = json.writeValueAsBytes(shipmentEvent(order, "evt-1", "DELIVERED", secondsAgo(5)));
        jdbc.update("UPDATE orders SET status = 'IN_TRANSIT' WHERE id = ?", order);

        mvc.perform(post(SHIPMENTS_WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Correlation-Id", "corr-cu24").header("X-Logistics-Signature", signer.signature(body)))
                .andExpect(status().isOk()).andExpect(header().string("X-Correlation-Id", "corr-cu24"));

        assertThat(jdbc.queryForList("SELECT correlation_id FROM shipment_tracking_events", String.class))
                .containsExactly("corr-cu24");
        assertThat(jdbc.queryForList("SELECT correlation_id FROM order_status_history WHERE to_status = 'DELIVERED'",
                String.class)).containsExactly("corr-cu24");
        assertThat(jdbc.queryForList("SELECT correlation_id FROM audit_events", String.class)).containsOnly("corr-cu24");
        assertThat(jdbc.queryForList("SELECT correlation_id FROM notifications", String.class)).containsOnly("corr-cu24");
        await().atMost(Duration.ofSeconds(10)).until(() -> notices.accepted().size() == 2);
        assertThat(notices.accepted()).extracting(request -> request.correlationId()).containsOnly("corr-cu24");
    }
}
