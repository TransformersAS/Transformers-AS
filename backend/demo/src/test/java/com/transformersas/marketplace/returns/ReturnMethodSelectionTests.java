package com.transformersas.marketplace.returns;

import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-19, elección del método de retorno (RF-109, A7): tras aprobar, el comprador consulta los métodos, elige uno y se crea el
 * retorno en logística y se registra su seguimiento (CU-25). Si logística no responde la devolución sigue aprobada y se
 * reintenta sin duplicar; si el método deja de estar disponible se pide elegir otro.
 */
class ReturnMethodSelectionTests extends ReturnsTestSupport {
    @Autowired SimulatedLogisticsGateway gateway;

    private Session buyer;
    private Session seller;
    private long buyerId;

    @BeforeEach
    void seed() throws Exception {
        gateway.reset();
        seller = sellerOfStore("vendedor@example.com", 1);
        buyer = sessionWithRole("comprador@example.com", "COMPRADOR");
        buyerId = accountIdOf("comprador@example.com");
    }

    private long approved() throws Exception {
        DeliveredOrder order = deliveredOrder(buyerId, LocalDateTime.now().minusDays(3));
        long id = idOf(requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "No enciende")
                .andExpect(status().isCreated()), "id");
        perform(seller, post(SELLER_RETURNS + "/" + id + "/review")).andExpect(status().isOk());
        perform(seller, post(SELLER_RETURNS + "/" + id + "/approve")).andExpect(status().isOk());
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions choose(Session session, long id, String method)
            throws Exception {
        return perform(session, post(RETURNS + "/" + id + "/return-method").contentType("application/json")
                .content("{\"method\":" + (method == null ? "null" : "\"" + method + "\"") + "}"));
    }

    private int shipments() {
        return count("return_shipments");
    }

    @Test
    void theBuyerSeesTheMethodsLogisticsOffersOnlyOnceTheReturnIsApproved() throws Exception {
        DeliveredOrder order = deliveredOrder(buyerId, LocalDateTime.now().minusDays(3));
        long requested = idOf(requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "x")
                .andExpect(status().isCreated()), "id");
        perform(buyer, get(RETURNS + "/" + requested + "/return-methods")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_INVALID_STATE")));

        long id = approved();

        perform(buyer, get(RETURNS + "/" + id + "/return-methods")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("PICKUP", "DROP_OFF")))
                .andExpect(jsonPath("$[0].label").isNotEmpty());
    }

    @Test
    void choosingCreatesTheReturnInLogisticsAndRegistersItsTrackingTogetherWithTheChoice() throws Exception {
        long id = approved();

        choose(buyer, id, "PICKUP").andExpect(status().isOk()).andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.returnMethodCode", is("PICKUP")))
                .andExpect(jsonPath("$.methodSelectionOverdue", is(false)));

        assertThat(jdbc.queryForMap("SELECT provider_return_id, tracking_code, status, buyer_account_id, store_id "
                + "FROM return_shipments WHERE return_id = ?", id)).containsEntry("provider_return_id", "SIM-return-" + id)
                .containsEntry("tracking_code", "TRK-R" + id).containsEntry("status", "PICKUP_PENDING")
                .containsEntry("buyer_account_id", buyerId).containsEntry("store_id", 1L);
        assertThat(gateway.distinctReturns()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM return_events ORDER BY id", String.class))
                .containsExactly("REQUESTED", "REVIEW_STARTED", "APPROVED", "METHOD_CHOSEN");
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE entity_type = 'RETURN' ORDER BY id",
                String.class)).contains("RETURN_METHOD_CHOSEN", "RETURN_SHIPMENT_REGISTERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'RETURN_METHOD_CHOSEN' AND "
                + "recipient_type = 'STORE'", Integer.class)).isEqualTo(1);
        perform(buyer, get("/api/returns/" + id + "/tracking")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKUP_PENDING"));
        perform(buyer, get(RETURNS + "/" + id + "/return-methods")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_METHOD_ALREADY_CHOSEN")));
    }

    @Test
    void choosingTheSameMethodAgainChangesNothingAndAnotherOneIsRefused() throws Exception {
        long id = approved();
        choose(buyer, id, "PICKUP").andExpect(status().isOk());
        int requests = gateway.requestCount();

        choose(buyer, id, "PICKUP").andExpect(status().isOk()).andExpect(jsonPath("$.returnMethodCode", is("PICKUP")));
        choose(buyer, id, "DROP_OFF").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_METHOD_ALREADY_CHOSEN")));

        assertThat(gateway.requestCount()).isEqualTo(requests);
        assertThat(shipments()).isEqualTo(1);
        assertThat(gateway.distinctReturns()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM return_events WHERE event_type = 'METHOD_CHOSEN'",
                String.class)).hasSize(1);
    }

    // ---------- Logística no responde: sigue Aprobada y se reintenta ----------

    @Test
    void whenLogisticsDoesNotAnswerTheBuyerGetsAClearErrorTheReturnStaysApprovedAndTheRetryWorks() throws Exception {
        long id = approved();
        gateway.setMode(Mode.UNAVAILABLE);

        choose(buyer, id, "PICKUP").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("RETURN_LOGISTICS_UNAVAILABLE")))
                .andExpect(jsonPath("$.message", containsString("sigue aprobada")));
        perform(buyer, get(RETURNS + "/" + id + "/return-methods")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("RETURN_LOGISTICS_UNAVAILABLE")));

        assertThat(jdbc.queryForObject("SELECT status FROM return_requests", String.class)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT return_method_code FROM return_requests", String.class)).isNull();
        assertThat(shipments()).isZero();
        assertThat(count("return_events")).isEqualTo(3); // solicitud, revisión y aprobación: nada de más
        gateway.setMode(Mode.OK);
        choose(buyer, id, "PICKUP").andExpect(status().isOk()).andExpect(jsonPath("$.returnMethodCode", is("PICKUP")));
        assertThat(shipments()).isEqualTo(1);
        assertThat(gateway.distinctReturns()).isEqualTo(1);
    }

    @Test
    void aMethodThatIsNoLongerAvailableAsksForAnotherOneWhichThenWorks() throws Exception {
        long id = approved();
        gateway.setUnavailableReturnMethods(List.of("PICKUP"));

        choose(buyer, id, "PICKUP").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_METHOD_UNAVAILABLE")))
                .andExpect(jsonPath("$.details.field", is("method")))
                .andExpect(jsonPath("$.message", containsString("elige otro")));
        assertThat(jdbc.queryForObject("SELECT return_method_code FROM return_requests", String.class)).isNull();
        perform(buyer, get(RETURNS + "/" + id + "/return-methods")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].code", is("DROP_OFF")));

        choose(buyer, id, "DROP_OFF").andExpect(status().isOk()).andExpect(jsonPath("$.returnMethodCode", is("DROP_OFF")));
        assertThat(shipments()).isEqualTo(1);
    }

    @Test
    void aDefinitiveRejectionOfTheMethodListIsABadGatewayNotAnUnavailableService() throws Exception {
        long id = approved();
        gateway.setMode(Mode.REJECT);

        perform(buyer, get(RETURNS + "/" + id + "/return-methods")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code", is("RETURN_LOGISTICS_REJECTED")));
        choose(buyer, id, "PICKUP").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_METHOD_UNAVAILABLE")));
        assertThat(shipments()).isZero();
    }

    // ---------- Quién y cuándo ----------

    @Test
    void onlyTheBuyerOfAnApprovedReturnCanChooseAndTheMethodIsRequired() throws Exception {
        long id = approved();
        createAccount("otra@example.com", "COMPRADOR");
        Session stranger = login("otra@example.com");
        DeliveredOrder order = deliveredOrder(buyerId, LocalDateTime.now().minusDays(3));
        long notApproved = idOf(requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "x")
                .andExpect(status().isCreated()), "id");

        choose(stranger, id, "PICKUP").andExpect(status().isNotFound());
        perform(stranger, get(RETURNS + "/" + id + "/return-methods")).andExpect(status().isNotFound());
        choose(seller, id, "PICKUP").andExpect(status().isForbidden());
        choose(buyer, notApproved, "PICKUP").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_INVALID_STATE")));
        choose(buyer, id, " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("RETURN_METHOD_REQUIRED"))).andExpect(jsonPath("$.details.field", is("method")));
        choose(buyer, id, null).andExpect(status().isBadRequest());
        assertThat(shipments()).isZero();
        assertThat(gateway.distinctReturns()).isZero();
    }

    @Test
    void twoSimultaneousChoicesOfTheSameMethodCreateOneReturnAndOneTracking() throws Exception {
        long id = approved();
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<CompletableFuture<Integer>> calls = List.of(1, 2).stream().map(n -> CompletableFuture.supplyAsync(() -> {
            try {
                barrier.await();
                return choose(buyer, id, "PICKUP").andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();

        List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();

        assertThat(statuses).containsOnly(200);
        assertThat(shipments()).isEqualTo(1);
        assertThat(gateway.distinctReturns()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM return_events WHERE event_type = 'METHOD_CHOSEN'",
                String.class)).hasSize(1);
    }
}
