package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.application.dto.RegisterReturnShipmentCommand;
import com.transformersas.marketplace.logistics.application.dto.ReturnDeliveredToSeller;
import com.transformersas.marketplace.logistics.application.usecase.PollActiveTrackingUseCase;
import com.transformersas.marketplace.logistics.application.usecase.RegisterReturnShipmentUseCase;
import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
import com.transformersas.marketplace.notifications.infrastructure.gateway.SimulatedExternalNotificationGateway;
import com.transformersas.marketplace.support.AbstractTrackingTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-25 (RF-110, RF-051, A1 a A8, RNF-043, RNF-045, RNF-046): seguimiento logístico de una devolución aprobada por
 * CU-19, desde Recogida pendiente hasta Entregado al vendedor, con máximo tres recogidas fallidas.
 */
@RecordApplicationEvents
class ReturnTrackingTests extends AbstractTrackingTest {

    private static final long RETURN = 501;

    @Autowired RegisterReturnShipmentUseCase register;
    @Autowired PollActiveTrackingUseCase poll;
    @Autowired ApplicationEvents applicationEvents;

    private Long buyerId;
    private Session buyer;
    private Session seller;

    @BeforeEach
    void setUp() throws Exception {
        buyerId = createAccount("buyer@example.com", "COMPRADOR");
        buyer = login("buyer@example.com");
        createAccount("seller@example.com", "VENDEDOR");
        seller = login("seller@example.com");
        seedStore(2, "Otra tienda");
        register.execute(new RegisterReturnShipmentCommand(RETURN, buyerId, 1L, "SIM-return-" + RETURN, "TRK-R" + RETURN));
        // En producción el registro precede a lo que informa logística; las pruebas fechan las actualizaciones en el
        // pasado, así que se adelanta el registro para que la línea de tiempo conserve el orden real.
        jdbc.update("UPDATE return_tracking_events SET occurred_at = ?", LocalDateTime.now().minusMinutes(10));
    }

    private void expectResult(ResultActions actions, String result) throws Exception {
        actions.andExpect(status().isOk()).andExpect(jsonPath("$.result").value(result));
    }

    private ResultActions send(String eventId, String type, long secondsAgo) throws Exception {
        return sendReturnEvent(RETURN, eventId, type, secondsAgo(secondsAgo));
    }

    private ResultActions buyerTracking() throws Exception {
        return perform(buyer, get("/api/returns/" + RETURN + "/tracking"));
    }

    private ResultActions sellerTracking() throws Exception {
        return performAsSeller(seller, 1, get("/api/seller/returns/" + RETURN + "/tracking"));
    }

    private List<String> eventOutcomes() {
        return jdbc.queryForList("SELECT CONCAT(event_type, ':', outcome) FROM return_tracking_events ORDER BY id",
                String.class);
    }

    private ReturnTrackingUpdate update(String eventId, ReturnEventType type, long secondsAgo) {
        return new ReturnTrackingUpdate(eventId, "SIM-return-" + RETURN, "TRK-R" + RETURN, type, secondsAgo(secondsAgo),
                null, null, null);
    }

    // ---------- Registro por CU-19 (pasos 1 y 2) ----------

    @Test
    void registeringTheReturnLeavesItPickupPendingWithItsFirstTimelineEntryAndAudit() throws Exception {
        assertThat(returnStatus(RETURN)).isEqualTo("PICKUP_PENDING");
        assertThat(eventOutcomes()).containsExactly("PICKUP_SCHEDULED:APPLIED");
        assertThat(jdbc.queryForMap("SELECT source, provider_event_id FROM return_tracking_events"))
                .containsEntry("source", "SYSTEM").containsEntry("provider_event_id", "registered-" + RETURN);
        assertThat(jdbc.queryForMap("SELECT action, actor_type, outcome, entity_id FROM audit_events"))
                .containsEntry("action", "RETURN_SHIPMENT_REGISTERED").containsEntry("actor_type", "SYSTEM")
                .containsEntry("entity_id", String.valueOf(RETURN));

        buyerTracking().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PICKUP_PENDING"))
                .andExpect(jsonPath("$.tracking").value(true)).andExpect(jsonPath("$.failedPickups").value(0))
                .andExpect(jsonPath("$.maxFailedPickups").value(3)).andExpect(jsonPath("$.canRequestNewPickup").value(false))
                .andExpect(jsonPath("$.events", hasSize(1)));
    }

    @Test
    void registeringTheSameReturnTwiceIsIdempotent() {
        var again = register.execute(new RegisterReturnShipmentCommand(RETURN, buyerId, 1L, "SIM-return-" + RETURN,
                "TRK-R" + RETURN));

        assertThat(again.created()).isFalse();
        assertThat(again.shipment().status().name()).isEqualTo("PICKUP_PENDING");
        assertThat(count("return_shipments")).isEqualTo(1);
        assertThat(count("return_tracking_events")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void registrationRejectsInvalidData() {
        assertThatThrownBy(() -> new RegisterReturnShipmentCommand(0L, buyerId, 1L, "ref", "trk"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegisterReturnShipmentCommand(1L, null, 1L, "ref", "trk"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegisterReturnShipmentCommand(1L, buyerId, -1L, "ref", "trk"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegisterReturnShipmentCommand(1L, buyerId, 1L, " ", "trk"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegisterReturnShipmentCommand(1L, buyerId, 1L, "ref", "t".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Flujo principal (pasos 3 a 15) ----------

    @Test
    void mainFlow_pickedUpInReturnDeliveredToSeller_finishesCu25AndHandsOverToCu19() throws Exception {
        expectResult(send("evt-1", "PICKED_UP", 30), "APPLIED");
        assertThat(returnStatus(RETURN)).isEqualTo("PICKED_UP");
        assertThat(jdbc.queryForObject("SELECT picked_up_at FROM return_shipments", Object.class)).isNotNull();
        Map<String, Object> transit = returnEvent(RETURN, "evt-2", "IN_TRANSIT", secondsAgo(20));
        transit.put("location", "Bogotá");
        expectResult(signedPost(RETURNS_WEBHOOK, transit), "APPLIED");
        assertThat(returnStatus(RETURN)).isEqualTo("IN_RETURN");
        Map<String, Object> delivered = returnEvent(RETURN, "evt-3", "DELIVERED_TO_SELLER", secondsAgo(10));
        delivered.put("evidence", Map.of("type", "SIGNATURE", "reference", "POD-R-9"));
        expectResult(signedPost(RETURNS_WEBHOOK, delivered), "APPLIED");

        assertThat(returnStatus(RETURN)).isEqualTo("DELIVERED_TO_SELLER");
        assertThat(jdbc.queryForObject("SELECT delivered_at FROM return_shipments", Object.class)).isNotNull();
        // CU-25 termina: ya no se consulta.
        assertThat(jdbc.queryForObject("SELECT tracking_active FROM return_shipments", Boolean.class)).isFalse();
        assertThat(eventOutcomes()).containsExactly("PICKUP_SCHEDULED:APPLIED", "PICKED_UP:APPLIED",
                "IN_TRANSIT:APPLIED", "DELIVERED_TO_SELLER:APPLIED");
        // Paso 15: CU-19 recibe el evento para continuar con la inspección.
        List<ReturnDeliveredToSeller> handovers = applicationEvents.stream(ReturnDeliveredToSeller.class).toList();
        assertThat(handovers).hasSize(1);
        assertThat(handovers.get(0).returnId()).isEqualTo(RETURN);
        assertThat(handovers.get(0).buyerAccountId()).isEqualTo(buyerId);
        assertThat(handovers.get(0).storeId()).isEqualTo(1L);
        // Notificaciones: comprador en cada avance; comprador y vendedor en la entrega.
        assertThat(notificationKeys()).containsExactly("return-" + RETURN + "-PICKED_UP-evt-1",
                "return-" + RETURN + "-IN_RETURN-evt-2", "return-" + RETURN + "-DELIVERED_TO_SELLER-evt-3",
                "return-" + RETURN + "-DELIVERED_TO_SELLER-evt-3-STORE");
        assertThat(jdbc.queryForList("SELECT recipient_type FROM notifications WHERE event_key LIKE '%evt-3%' ORDER BY id",
                String.class)).containsExactly("BUYER", "STORE");
        await().atMost(Duration.ofSeconds(10)).until(() -> notices.accepted().size() == 4);
        // RNF-009: Recogido y Entregado al vendedor quedan auditados con origen LOGISTICS.
        assertThat(jdbc.queryForList("SELECT details FROM audit_events WHERE action = 'RETURN_STATUS_CHANGED' ORDER BY id",
                String.class)).hasSize(3).anyMatch(details -> details.contains("PICKED_UP"))
                .anyMatch(details -> details.contains("DELIVERED_TO_SELLER"));
        assertThat(jdbc.queryForList("SELECT DISTINCT actor_type FROM audit_events WHERE action = 'RETURN_STATUS_CHANGED'",
                String.class)).containsExactly("LOGISTICS");

        // RF-051: comprador y vendedor ven la línea de tiempo cronológica con la evidencia de entrega.
        for (ResultActions view : List.of(buyerTracking(), sellerTracking())) {
            view.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED_TO_SELLER"))
                    .andExpect(jsonPath("$.tracking").value(false)).andExpect(jsonPath("$.events", hasSize(4)))
                    .andExpect(jsonPath("$.events[0].type").value("PICKUP_SCHEDULED"))
                    .andExpect(jsonPath("$.events[1].type").value("PICKED_UP"))
                    .andExpect(jsonPath("$.events[2].location").value("Bogotá"))
                    .andExpect(jsonPath("$.events[3].evidence.reference").value("POD-R-9"))
                    .andExpect(jsonPath("$.deliveredAt").isNotEmpty()).andExpect(jsonPath("$.pickedUpAt").isNotEmpty());
        }
    }

    // ---------- A1 y A2: recogidas fallidas ----------

    @Test
    void a1_firstAndSecondFailedPickupsRegisterTheReasonAndAllowANewPickup() throws Exception {
        Map<String, Object> failed = returnEvent(RETURN, "evt-1", "PICKUP_FAILED", secondsAgo(50));
        failed.put("description", "Comprador ausente");
        expectResult(signedPost(RETURNS_WEBHOOK, failed), "APPLIED");
        assertThat(returnStatus(RETURN)).isEqualTo("PICKUP_FAILED");
        buyerTracking().andExpect(jsonPath("$.failedPickups").value(1)).andExpect(jsonPath("$.canRequestNewPickup").value(true))
                .andExpect(jsonPath("$.pickupStopped").value(false)).andExpect(jsonPath("$.tracking").value(true))
                .andExpect(jsonPath("$.events[1].description").value("Comprador ausente"));

        // Logística programa una nueva recogida y vuelve a fallar: sigue habiendo una más.
        expectResult(send("evt-2", "PICKUP_SCHEDULED", 40), "APPLIED");
        assertThat(returnStatus(RETURN)).isEqualTo("PICKUP_PENDING");
        expectResult(send("evt-3", "PICKUP_FAILED", 30), "APPLIED");
        buyerTracking().andExpect(jsonPath("$.failedPickups").value(2)).andExpect(jsonPath("$.canRequestNewPickup").value(true));

        // El tercer intento sí recoge: el flujo normal continúa.
        expectResult(send("evt-4", "PICKED_UP", 20), "APPLIED");
        assertThat(returnStatus(RETURN)).isEqualTo("PICKED_UP");
        assertThat(count("audit_events")).isEqualTo(1 + 4); // registro + 4 cambios de estado
        assertThat(jdbc.queryForObject("SELECT pickup_stopped FROM return_shipments", Boolean.class)).isFalse();
    }

    @Test
    void a2_theThirdFailedPickupStopsNewPickupsAndInformsBothPartiesOnce() throws Exception {
        send("evt-1", "PICKUP_FAILED", 60).andExpect(status().isOk());
        send("evt-2", "PICKUP_SCHEDULED", 50).andExpect(status().isOk());
        send("evt-3", "PICKUP_FAILED", 40).andExpect(status().isOk());
        send("evt-4", "PICKUP_SCHEDULED", 30).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT pickup_stopped FROM return_shipments", Boolean.class)).isFalse();

        expectResult(send("evt-5", "PICKUP_FAILED", 20), "APPLIED");

        assertThat(returnStatus(RETURN)).isEqualTo("PICKUP_FAILED");
        assertThat(jdbc.queryForMap("SELECT failed_pickups, pickup_stopped, tracking_active FROM return_shipments"))
                .containsEntry("failed_pickups", 3).containsEntry("pickup_stopped", true)
                .containsEntry("tracking_active", false);
        // RNF-009: el tercer intento fallido que detiene las recogidas queda auditado.
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE action = 'RETURN_PICKUP_STOPPED'", String.class))
                .hasSize(1);
        // Se informa al comprador y al vendedor que el retorno no pudo continuar.
        assertThat(notificationKeys()).contains("return-" + RETURN + "-PICKUP_FAILED-evt-5",
                "return-" + RETURN + "-PICKUP_FAILED-evt-5-STORE");
        buyerTracking().andExpect(jsonPath("$.failedPickups").value(3)).andExpect(jsonPath("$.pickupStopped").value(true))
                .andExpect(jsonPath("$.canRequestNewPickup").value(false)).andExpect(jsonPath("$.tracking").value(false))
                .andExpect(jsonPath("$.status").value("PICKUP_FAILED"));

        // Después no se acepta ningún avance automático: se conserva solo para trazabilidad.
        expectResult(send("evt-6", "PICKUP_SCHEDULED", 10), "OUT_OF_ORDER");
        expectResult(send("evt-7", "PICKED_UP", 5), "OUT_OF_ORDER");
        assertThat(returnStatus(RETURN)).isEqualTo("PICKUP_FAILED");
        assertThat(jdbc.queryForObject("SELECT failed_pickups FROM return_shipments", Integer.class)).isEqualTo(3);
    }

    // ---------- A3: novedad logística ----------

    @Test
    void a3_logisticsIssueKeepsTheHistoryAndReturnsToTheNormalFlowOnANewValidUpdate() throws Exception {
        send("evt-1", "PICKED_UP", 60).andExpect(status().isOk());
        send("evt-2", "IN_TRANSIT", 50).andExpect(status().isOk());
        Map<String, Object> incident = returnEvent(RETURN, "evt-3", "INCIDENT", secondsAgo(40));
        incident.put("description", "Paquete retenido en aduana");
        expectResult(signedPost(RETURNS_WEBHOOK, incident), "APPLIED");
        assertThat(returnStatus(RETURN)).isEqualTo("LOGISTICS_ISSUE");
        buyerTracking().andExpect(jsonPath("$.status").value("LOGISTICS_ISSUE"))
                .andExpect(jsonPath("$.events", hasSize(4))).andExpect(jsonPath("$.events[3].description").value("Paquete retenido en aduana"));

        expectResult(send("evt-4", "INCIDENT", 30), "APPLIED"); // otra novedad seguida
        expectResult(send("evt-5", "IN_TRANSIT", 20), "APPLIED");
        expectResult(send("evt-6", "DELIVERED_TO_SELLER", 10), "APPLIED");

        assertThat(returnStatus(RETURN)).isEqualTo("DELIVERED_TO_SELLER");
        assertThat(eventOutcomes()).hasSize(7).doesNotContain("PICKED_UP:OUT_OF_ORDER");
    }

    @Test
    void a3_anIssueDuringPickupResolvesToPickedUpOrToAFailedPickup() throws Exception {
        send("evt-1", "INCIDENT", 40).andExpect(status().isOk());
        expectResult(send("evt-2", "PICKED_UP", 30), "APPLIED");
        assertThat(returnStatus(RETURN)).isEqualTo("PICKED_UP");
        // Ya recogida, una novedad no puede convertirse en recogida fallida ni volver a recogida.
        send("evt-3", "INCIDENT", 20).andExpect(status().isOk());
        expectResult(send("evt-4", "PICKUP_FAILED", 15), "OUT_OF_ORDER");
        expectResult(send("evt-5", "PICKED_UP", 10), "OUT_OF_ORDER");
        assertThat(returnStatus(RETURN)).isEqualTo("LOGISTICS_ISSUE");
        expectResult(send("evt-6", "DELIVERED_TO_SELLER", 5), "APPLIED");
    }

    @Test
    void a3_aFailedPickupCanFollowAnIssueThatHappenedBeforeThePickup() throws Exception {
        send("evt-1", "INCIDENT", 40).andExpect(status().isOk());

        expectResult(send("evt-2", "PICKUP_FAILED", 30), "APPLIED");

        assertThat(returnStatus(RETURN)).isEqualTo("PICKUP_FAILED");
        assertThat(jdbc.queryForObject("SELECT failed_pickups FROM return_shipments", Integer.class)).isEqualTo(1);
    }

    // ---------- A4 y A5: evento repetido y fuera de orden ----------

    @Test
    void a4_repeatedEventDoesNotDuplicateTheStateChangeNorTheNotifications() throws Exception {
        expectResult(send("evt-1", "PICKED_UP", 30), "APPLIED");
        expectResult(send("evt-1", "PICKED_UP", 30), "DUPLICATE");
        expectResult(send("evt-1", "PICKED_UP", 30), "DUPLICATE");

        assertThat(eventOutcomes()).containsExactly("PICKUP_SCHEDULED:APPLIED", "PICKED_UP:APPLIED");
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE action = 'RETURN_STATUS_CHANGED'", String.class))
                .hasSize(1);
    }

    @Test
    void a5_anEarlierStateReceivedLateNeverMovesTheReturnBack() throws Exception {
        send("evt-2", "IN_TRANSIT", 30).andExpect(status().isOk());

        expectResult(send("evt-1", "PICKED_UP", 40), "OUT_OF_ORDER");
        expectResult(send("evt-3", "PICKUP_SCHEDULED", 35), "OUT_OF_ORDER");
        expectResult(send("evt-4", "PICKUP_FAILED", 34), "OUT_OF_ORDER");
        expectResult(send("evt-5", "IN_TRANSIT", 20), "RECORDED"); // más información del mismo estado

        assertThat(returnStatus(RETURN)).isEqualTo("IN_RETURN");
        assertThat(eventOutcomes()).containsExactly("PICKUP_SCHEDULED:APPLIED", "IN_TRANSIT:APPLIED",
                "PICKED_UP:OUT_OF_ORDER", "PICKUP_SCHEDULED:OUT_OF_ORDER", "PICKUP_FAILED:OUT_OF_ORDER",
                "IN_TRANSIT:RECORDED");
        // Entregar al vendedor sin haber recogido nada tampoco se acepta.
        long other = 502;
        register.execute(new RegisterReturnShipmentCommand(other, buyerId, 1L, "SIM-return-" + other, "TRK-R" + other));
        sendReturnEvent(other, "evt-x", "DELIVERED_TO_SELLER", secondsAgo(5)).andExpect(jsonPath("$.result").value("OUT_OF_ORDER"));
        assertThat(returnStatus(other)).isEqualTo("PICKUP_PENDING");
    }

    @Test
    void anAlreadyPickedUpReturnRecordsRepeatedPickupsAndSchedulesWithoutChangingState() throws Exception {
        send("evt-1", "PICKED_UP", 30).andExpect(status().isOk());

        expectResult(send("evt-2", "PICKED_UP", 20), "RECORDED");
        expectResult(send("evt-3", "PICKUP_SCHEDULED", 10), "OUT_OF_ORDER");

        expectResult(send("evt-4", "PICKUP_SCHEDULED", 5), "OUT_OF_ORDER");
        assertThat(returnStatus(RETURN)).isEqualTo("PICKED_UP");
    }

    @Test
    void aRescheduledPickupWhilePendingIsRecordedWithoutChangingState() throws Exception {
        expectResult(send("evt-1", "PICKUP_SCHEDULED", 10), "RECORDED");

        assertThat(returnStatus(RETURN)).isEqualTo("PICKUP_PENDING");
        assertThat(count("notifications")).isZero();
    }

    @Test
    void rnf043_theSameReturnEventSentConcurrentlyHasASingleEffect() throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(6);
        List<java.util.concurrent.CompletableFuture<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    barrier.await();
                    var result = send("evt-race", "PICKUP_FAILED", 5).andReturn();
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    return json.readTree(result.getResponse().getContentAsString()).get("result").asString();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }));
        }
        List<String> results = futures.stream().map(java.util.concurrent.CompletableFuture::join).toList();

        assertThat(results).containsOnlyOnce("APPLIED");
        assertThat(jdbc.queryForObject("SELECT failed_pickups FROM return_shipments", Integer.class)).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(1);
    }

    // ---------- Identificación y autenticación del webhook ----------

    @Test
    void anUpdateWithAnotherTrackingCodeOrUnknownReturnIsRejected() throws Exception {
        Map<String, Object> wrong = returnEvent(RETURN, "evt-1", "PICKED_UP", secondsAgo(5));
        wrong.put("trackingCode", "TRK-R999");
        signedPost(RETURNS_WEBHOOK, wrong).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_SHIPMENT_MISMATCH"));
        assertThat(jdbc.queryForList("SELECT outcome FROM audit_events WHERE action = 'TRACKING_UPDATE_REJECTED'",
                String.class)).containsExactly("FAILURE");

        Map<String, Object> unknown = returnEvent(RETURN, "evt-2", "PICKED_UP", secondsAgo(5));
        unknown.put("returnId", "SIM-return-777");
        signedPost(RETURNS_WEBHOOK, unknown).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RETURN_SHIPMENT_NOT_FOUND"));

        assertThat(returnStatus(RETURN)).isEqualTo("PICKUP_PENDING");
        assertThat(count("return_tracking_events")).isEqualTo(1);
    }

    @Test
    void theReturnsWebhookRequiresTheSignatureAndIgnoresUnknownTypes() throws Exception {
        byte[] body = json.writeValueAsBytes(returnEvent(RETURN, "evt-1", "PICKED_UP", secondsAgo(5)));

        mvc.perform(post(RETURNS_WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        expectResult(send("evt-9", "LOST_IN_SPACE", 5), "IGNORED");
        Map<String, Object> invalid = returnEvent(RETURN, "evt-2", "PICKED_UP", secondsAgo(5));
        invalid.remove("returnId");
        signedPost(RETURNS_WEBHOOK, invalid).andExpect(status().isBadRequest());

        assertThat(returnStatus(RETURN)).isEqualTo("PICKUP_PENDING");
    }

    // ---------- A8: fallo del aviso externo ----------

    @Test
    void a8_externalNoticeFailureKeepsTheStateChangeVisible() throws Exception {
        notices.setMode(SimulatedExternalNotificationGateway.Mode.UNAVAILABLE);

        expectResult(send("evt-1", "PICKED_UP", 5), "APPLIED");

        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT external_status FROM notifications", String.class).equals("FAILED"));
        buyerTracking().andExpect(jsonPath("$.status").value("PICKED_UP"));
    }

    // ---------- Consulta al servicio logístico (paso 3, A6) ----------

    @Test
    void buyerAndSellerCanRefreshTheReturnAndTheProviderUpdatesAreProcessedInOrder() throws Exception {
        logistics.publishReturn(update("evt-2", ReturnEventType.IN_TRANSIT, 20));
        logistics.publishReturn(update("evt-1", ReturnEventType.PICKED_UP, 30));

        perform(buyer, post("/api/returns/" + RETURN + "/tracking/refresh")).andExpect(status().isOk())
                .andExpect(jsonPath("$.refresh").value("UPDATED")).andExpect(jsonPath("$.status").value("IN_RETURN"))
                .andExpect(jsonPath("$.events[1].source").value("POLLING"));
        // Inmediatamente después, otra consulta se limita para no saturar al proveedor.
        performAsSeller(seller, 1, post("/api/seller/returns/" + RETURN + "/tracking/refresh"))
                .andExpect(jsonPath("$.refresh").value("THROTTLED"));
        jdbc.update("UPDATE return_shipments SET last_polled_at = ?", LocalDateTime.now().minusMinutes(1));
        performAsSeller(seller, 1, post("/api/seller/returns/" + RETURN + "/tracking/refresh"))
                .andExpect(jsonPath("$.refresh").value("NO_CHANGES"));
    }

    @Test
    void a6_whenTheProviderIsUnavailableTheLastKnownStateStaysVisible() throws Exception {
        send("evt-1", "PICKED_UP", 30).andExpect(status().isOk());
        logistics.setMode(SimulatedLogisticsGateway.Mode.UNAVAILABLE);

        perform(buyer, post("/api/returns/" + RETURN + "/tracking/refresh")).andExpect(status().isOk())
                .andExpect(jsonPath("$.refresh").value("UNAVAILABLE")).andExpect(jsonPath("$.lastPollFailed").value(true))
                .andExpect(jsonPath("$.status").value("PICKED_UP")).andExpect(jsonPath("$.events", hasSize(2)));

        logistics.setMode(SimulatedLogisticsGateway.Mode.REJECT);
        jdbc.update("UPDATE return_shipments SET last_polled_at = ?", LocalDateTime.now().minusMinutes(1));
        perform(buyer, post("/api/returns/" + RETURN + "/tracking/refresh")).andExpect(jsonPath("$.refresh").value("UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT poll_failures FROM return_shipments", Integer.class)).isEqualTo(2);
        assertThat(returnStatus(RETURN)).isEqualTo("PICKED_UP");
    }

    @Test
    void refreshingAFinishedReturnDoesNotCallTheProvider() throws Exception {
        send("evt-1", "PICKED_UP", 30).andExpect(status().isOk());
        send("evt-2", "DELIVERED_TO_SELLER", 20).andExpect(status().isOk());

        perform(buyer, post("/api/returns/" + RETURN + "/tracking/refresh")).andExpect(jsonPath("$.refresh").value("NOT_TRACKED"));

        assertThat(logistics.requestCount()).isZero();
    }

    @Test
    void aPolledUpdateForAnotherReturnIsDiscardedWithoutBlockingTheRest() throws Exception {
        logistics.publishReturn(new ReturnTrackingUpdate("evt-x", "SIM-return-" + RETURN, "TRK-R999",
                ReturnEventType.DELIVERED_TO_SELLER, secondsAgo(40), null, null, null));
        logistics.publishReturn(update("evt-1", ReturnEventType.PICKED_UP, 30));

        perform(buyer, post("/api/returns/" + RETURN + "/tracking/refresh")).andExpect(jsonPath("$.refresh").value("UPDATED"))
                .andExpect(jsonPath("$.status").value("PICKED_UP"));
    }

    @Test
    void theSweepPollsActiveReturnsAndSkipsStoppedOnes() throws Exception {
        long stopped = 503;
        returnShipment(stopped, buyerId, 1, "PICKUP_FAILED", 3);
        jdbc.update("UPDATE return_shipments SET tracking_active = FALSE WHERE return_id = ?", stopped);
        logistics.publishReturn(update("evt-1", ReturnEventType.PICKED_UP, 30));

        assertThat(poll.execute()).isEqualTo(1);

        assertThat(returnStatus(RETURN)).isEqualTo("PICKED_UP");
        assertThat(poll.execute()).isZero();
    }

    // ---------- Autenticación y autorización (RNF-003, RNF-010) ----------

    @Test
    void trackingIsOnlyVisibleToItsBuyerAndItsStoreAndRequiresTheRightRole() throws Exception {
        mvc.perform(get("/api/returns/" + RETURN + "/tracking")).andExpect(status().isUnauthorized());
        perform(seller, get("/api/returns/" + RETURN + "/tracking")).andExpect(status().isForbidden());
        perform(seller, post("/api/returns/" + RETURN + "/tracking/refresh")).andExpect(status().isForbidden());
        performAsSeller(buyer, 1, get("/api/seller/returns/" + RETURN + "/tracking")).andExpect(status().isForbidden());
        perform(seller, get("/api/seller/returns/" + RETURN + "/tracking")).andExpect(status().isUnauthorized());

        createAccount("other-buyer@example.com", "COMPRADOR");
        Session otherBuyer = login("other-buyer@example.com");
        perform(otherBuyer, get("/api/returns/" + RETURN + "/tracking")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RETURN_NOT_FOUND"));
        perform(otherBuyer, post("/api/returns/" + RETURN + "/tracking/refresh")).andExpect(status().isNotFound());
        performAsSeller(seller, 2, get("/api/seller/returns/" + RETURN + "/tracking")).andExpect(status().isNotFound());
        performAsSeller(seller, 2, post("/api/seller/returns/" + RETURN + "/tracking/refresh")).andExpect(status().isNotFound());
        perform(buyer, get("/api/returns/999999/tracking")).andExpect(status().isNotFound());
        assertThat(logistics.requestCount()).isZero();
    }

    @Test
    void a7_neitherBuyerNorSellerCanSetALogisticsStateByHand() throws Exception {
        perform(buyer, post("/api/returns/" + RETURN + "/tracking/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DELIVERED_TO_SELLER\"}")).andExpect(status().isBadRequest());
        performAsSeller(seller, 1, post("/api/seller/returns/" + RETURN + "/tracking/refresh")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED_TO_SELLER\"}"))
                .andExpect(status().isBadRequest());
        performAsSeller(seller, 1, post("/api/seller/returns/" + RETURN + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().is4xxClientError());

        assertThat(returnStatus(RETURN)).isEqualTo("PICKUP_PENDING");
    }

    @Test
    void rnf038_theCorrelationIdOfTheProviderRequestIsInEveryRelatedRecord() throws Exception {
        byte[] body = json.writeValueAsBytes(returnEvent(RETURN, "evt-1", "PICKED_UP", secondsAgo(5)));

        mvc.perform(post(RETURNS_WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Correlation-Id", "corr-cu25").header("X-Logistics-Signature", signer.signature(body)))
                .andExpect(status().isOk()).andExpect(header().string("X-Correlation-Id", "corr-cu25"));

        assertThat(jdbc.queryForList("SELECT correlation_id FROM return_tracking_events WHERE provider_event_id = 'evt-1'",
                String.class)).containsExactly("corr-cu25");
        assertThat(jdbc.queryForList("SELECT correlation_id FROM audit_events WHERE action = 'RETURN_STATUS_CHANGED'",
                String.class)).containsExactly("corr-cu25");
        assertThat(jdbc.queryForList("SELECT correlation_id FROM notifications", String.class)).containsExactly("corr-cu25");
    }
}
