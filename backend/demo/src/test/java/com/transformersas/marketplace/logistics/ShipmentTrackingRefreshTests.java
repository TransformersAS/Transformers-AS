package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.application.usecase.PollActiveTrackingUseCase;
import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
import com.transformersas.marketplace.support.AbstractTrackingTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-24 por consulta (paso 1, A5, A7, RNF-045, RNF-046): el marketplace consulta al servicio logístico cuando el
 * usuario pide actualizar y en el barrido periódico; si el servicio falla conserva el último seguimiento conocido.
 */
class ShipmentTrackingRefreshTests extends AbstractTrackingTest {

    @Autowired PollActiveTrackingUseCase poll;

    private Long buyerId;
    private Session buyer;
    private Session seller;
    private Session otherSeller;
    private long order;

    @BeforeEach
    void setUp() throws Exception {
        buyerId = createAccount("buyer@example.com", "COMPRADOR");
        buyer = login("buyer@example.com");
        seedStore(2, "Otra tienda");
        seller = sellerOfStore("seller@example.com", 1);
        otherSeller = sellerOfStore("seller2@example.com", 2);
        order = shippedOrder(buyerId, "READY_FOR_DISPATCH");
    }

    private TrackingUpdate update(long forOrder, String eventId, ShipmentEventType type, long secondsAgo) {
        return new TrackingUpdate(eventId, "SIM-order-" + forOrder, "TRK-" + forOrder, type, secondsAgo(secondsAgo),
                null, null, null);
    }

    private ResultActions buyerRefresh() throws Exception {
        return perform(buyer, post("/api/orders/" + order + "/tracking/refresh"));
    }

    private ResultActions sellerRefresh() throws Exception {
        return performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/tracking/refresh"));
    }

    private void allowNextRefresh() {
        jdbc.update("UPDATE shipments SET last_polled_at = ? WHERE order_id = ?", LocalDateTime.now().minusMinutes(1), order);
    }

    // ---------- Consulta pedida por el usuario ----------

    @Test
    void buyerRefreshProcessesWhatTheProviderReportsInChronologicalOrder() throws Exception {
        // El proveedor las devuelve desordenadas: el marketplace las procesa por fecha de ocurrencia.
        logistics.publish(update(order, "evt-2", ShipmentEventType.IN_TRANSIT, 20));
        logistics.publish(update(order, "evt-1", ShipmentEventType.PICKED_UP, 30));

        buyerRefresh().andExpect(status().isOk()).andExpect(jsonPath("$.refresh").value("UPDATED"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT")).andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].type").value("PICKED_UP"))
                .andExpect(jsonPath("$.events[0].source").value("POLLING"))
                .andExpect(jsonPath("$.lastPollFailed").value(false)).andExpect(jsonPath("$.lastPolledAt").isNotEmpty());

        assertThat(historyStatuses(order)).containsExactly("READY_FOR_DISPATCH", "PICKED_UP", "IN_TRANSIT");
        assertThat(count("notifications")).isEqualTo(2);
    }

    @Test
    void refreshesInARowAreThrottledWithoutCallingTheProviderAgain() throws Exception {
        buyerRefresh().andExpect(jsonPath("$.refresh").value("NO_CHANGES"));
        int calls = logistics.requestCount();

        buyerRefresh().andExpect(status().isOk()).andExpect(jsonPath("$.refresh").value("THROTTLED"));
        sellerRefresh().andExpect(jsonPath("$.refresh").value("THROTTLED"));

        assertThat(logistics.requestCount()).isEqualTo(calls);
        allowNextRefresh();
        sellerRefresh().andExpect(jsonPath("$.refresh").value("NO_CHANGES"));
        assertThat(logistics.requestCount()).isEqualTo(calls + 1);
    }

    @Test
    void sellerCanRefreshTheirOrdersTracking() throws Exception {
        logistics.publish(update(order, "evt-1", ShipmentEventType.PICKED_UP, 30));

        sellerRefresh().andExpect(status().isOk()).andExpect(jsonPath("$.refresh").value("UPDATED"))
                .andExpect(jsonPath("$.status").value("PICKED_UP"));
    }

    @Test
    void a5_theSameEventReceivedByWebhookAndByPollingHasASingleEffect() throws Exception {
        sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(30)).andExpect(status().isOk());
        logistics.publish(new TrackingUpdate("evt-1", "SIM-order-" + order, "TRK-" + order, ShipmentEventType.PICKED_UP,
                secondsAgo(30), null, null, null));

        buyerRefresh().andExpect(jsonPath("$.refresh").value("NO_CHANGES")).andExpect(jsonPath("$.events", hasSize(1)));

        assertThat(historyStatuses(order)).containsExactly("READY_FOR_DISPATCH", "PICKED_UP");
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void aPolledUpdateForAnotherShipmentIsDiscardedWithoutBlockingTheRest() throws Exception {
        logistics.publish(new TrackingUpdate("evt-x", "SIM-order-" + order, "TRK-999", ShipmentEventType.DELIVERED,
                secondsAgo(40), null, null, null));
        logistics.publish(update(order, "evt-1", ShipmentEventType.PICKED_UP, 30));

        buyerRefresh().andExpect(jsonPath("$.refresh").value("UPDATED")).andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.events", hasSize(1)));

        assertThat(count("audit_events") >= 1).isTrue();
    }

    // ---------- A7: servicio logístico no disponible ----------

    @Test
    void a7_whenTheProviderFailsTheLastKnownTrackingStaysVisibleAndNoStatesAreInvented() throws Exception {
        sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(30)).andExpect(status().isOk());
        logistics.setMode(SimulatedLogisticsGateway.Mode.UNAVAILABLE);

        buyerRefresh().andExpect(status().isOk()).andExpect(jsonPath("$.refresh").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.lastPollFailed").value(true)).andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.events", hasSize(1)));
        assertThat(jdbc.queryForObject("SELECT poll_failures FROM shipments WHERE order_id = ?", Integer.class, order))
                .isEqualTo(1);
        allowNextRefresh();
        sellerRefresh().andExpect(jsonPath("$.refresh").value("UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT poll_failures FROM shipments WHERE order_id = ?", Integer.class, order))
                .isEqualTo(2);

        // El tracking sigue consultable aunque logística esté caída.
        perform(buyer, get("/api/orders/" + order + "/tracking")).andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.lastPollFailed").value(true));
        assertThat(orderStatus(order)).isEqualTo("PICKED_UP");

        // Cuando vuelve, se consulta de nuevo y el contador de fallos se reinicia.
        logistics.setMode(SimulatedLogisticsGateway.Mode.OK);
        logistics.publish(update(order, "evt-2", ShipmentEventType.IN_TRANSIT, 10));
        allowNextRefresh();
        buyerRefresh().andExpect(jsonPath("$.refresh").value("UPDATED")).andExpect(jsonPath("$.lastPollFailed").value(false))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
    }

    @Test
    void aDefinitiveRejectionFromTheProviderAlsoKeepsTheLastKnownTracking() throws Exception {
        logistics.setMode(SimulatedLogisticsGateway.Mode.REJECT);

        buyerRefresh().andExpect(jsonPath("$.refresh").value("UNAVAILABLE")).andExpect(jsonPath("$.lastPollFailed").value(true));

        assertThat(orderStatus(order)).isEqualTo("READY_FOR_DISPATCH");
    }

    // ---------- Sin envío o ya finalizado ----------

    @Test
    void refreshingAFinishedShipmentDoesNotCallTheProvider() throws Exception {
        sendShipmentEvent(order, "evt-1", "IN_TRANSIT", secondsAgo(30)).andExpect(status().isOk());
        sendShipmentEvent(order, "evt-2", "DELIVERED", secondsAgo(20)).andExpect(status().isOk());
        int calls = logistics.requestCount();

        buyerRefresh().andExpect(jsonPath("$.refresh").value("NOT_TRACKED")).andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.tracking").value(false));

        assertThat(logistics.requestCount()).isEqualTo(calls);
    }

    @Test
    void anOrderWithoutShipmentShowsAnEmptyTracking() throws Exception {
        long notShipped = seedOrder(1, "IN_PREPARATION", product(), 1, "100.00");
        jdbc.update("UPDATE orders SET account_id = ? WHERE id = ?", buyerId, notShipped);

        perform(buyer, get("/api/orders/" + notShipped + "/tracking")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PREPARATION")).andExpect(jsonPath("$.events", hasSize(0)))
                .andExpect(jsonPath("$.trackingCode").doesNotExist()).andExpect(jsonPath("$.tracking").value(false));
        perform(buyer, post("/api/orders/" + notShipped + "/tracking/refresh"))
                .andExpect(jsonPath("$.refresh").value("NOT_TRACKED"));
    }

    // ---------- Barrido periódico ----------

    @Test
    void theSweepPollsActiveShipmentsThatAreDueAndSkipsFinishedOrRecentlyPolledOnes() throws Exception {
        long second = shippedOrder(buyerId, "READY_FOR_DISPATCH");
        long finished = shippedOrder(buyerId, "DELIVERED");
        jdbc.update("UPDATE shipments SET tracking_active = FALSE WHERE order_id = ?", finished);
        long recent = shippedOrder(buyerId, "READY_FOR_DISPATCH");
        jdbc.update("UPDATE shipments SET last_polled_at = ? WHERE order_id = ?", LocalDateTime.now(), recent);
        logistics.publish(update(order, "evt-1", ShipmentEventType.PICKED_UP, 30));
        logistics.publish(update(second, "evt-1", ShipmentEventType.IN_TRANSIT, 30));
        logistics.publish(update(recent, "evt-1", ShipmentEventType.PICKED_UP, 30));

        int polled = poll.execute();

        assertThat(polled).isEqualTo(2);
        assertThat(orderStatus(order)).isEqualTo("PICKED_UP");
        assertThat(orderStatus(second)).isEqualTo("IN_TRANSIT");
        assertThat(orderStatus(recent)).isEqualTo("READY_FOR_DISPATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE last_polled_at IS NOT NULL", Integer.class))
                .isEqualTo(3);
        // Una segunda pasada inmediata no repite nada: nada vence todavía.
        assertThat(poll.execute()).isZero();
    }

    @Test
    void theSweepSurvivesAProviderOutageAndRetriesLater() throws Exception {
        logistics.setMode(SimulatedLogisticsGateway.Mode.UNAVAILABLE);

        assertThat(poll.execute()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT poll_failures FROM shipments WHERE order_id = ?", Integer.class, order))
                .isEqualTo(1);

        logistics.setMode(SimulatedLogisticsGateway.Mode.OK);
        logistics.publish(update(order, "evt-1", ShipmentEventType.PICKED_UP, 5));
        jdbc.update("UPDATE shipments SET last_polled_at = ?", LocalDateTime.now().minusHours(1));
        assertThat(poll.execute()).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo("PICKED_UP");
        assertThat(jdbc.queryForObject("SELECT poll_failures FROM shipments WHERE order_id = ?", Integer.class, order))
                .isZero();
    }

    // ---------- Autenticación y autorización (RNF-003, RNF-010) ----------

    @Test
    void trackingRequiresAuthenticationAndTheRightRole() throws Exception {
        mvc.perform(get("/api/orders/" + order + "/tracking")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/seller/orders/" + order + "/tracking")).andExpect(status().isUnauthorized());
        // Un vendedor no entra por la puerta del comprador ni al revés.
        perform(seller, get("/api/orders/" + order + "/tracking")).andExpect(status().isForbidden());
        perform(seller, post("/api/orders/" + order + "/tracking/refresh")).andExpect(status().isForbidden());
        performAsSeller(buyer, 1, get("/api/seller/orders/" + order + "/tracking")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_ROLE_REQUIRED"));
        // Un vendedor sin tienda ni cabecera no tiene identidad de tienda.
        perform(sessionWithRole("sintienda@example.com", "VENDEDOR"), get("/api/seller/orders/" + order + "/tracking"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("STORE_IDENTITY_MISSING"));
    }

    @Test
    void trackingOfAnotherBuyersOrOtherStoresOrderIsIndistinguishableFromNotFound() throws Exception {
        createAccount("other-buyer@example.com", "COMPRADOR");
        Session otherBuyer = login("other-buyer@example.com");

        perform(otherBuyer, get("/api/orders/" + order + "/tracking")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        perform(otherBuyer, post("/api/orders/" + order + "/tracking/refresh")).andExpect(status().isNotFound());
        performAsSeller(otherSeller, 2, get("/api/seller/orders/" + order + "/tracking")).andExpect(status().isNotFound());
        performAsSeller(otherSeller, 2, post("/api/seller/orders/" + order + "/tracking/refresh"))
                .andExpect(status().isNotFound());
        perform(buyer, get("/api/orders/999999/tracking")).andExpect(status().isNotFound());
        // Consultar lo ajeno no llamó nunca al proveedor.
        assertThat(logistics.requestCount()).isZero();
    }

    @Test
    void refreshAcceptsAnEmptyBodyOrNoBody() throws Exception {
        perform(buyer, post("/api/orders/" + order + "/tracking/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk());
    }
}
