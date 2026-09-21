package com.transformersas.marketplace.returns;

import com.transformersas.marketplace.logistics.application.dto.ReturnDeliveredToSeller;
import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import com.transformersas.marketplace.returns.application.usecase.ReturnSweepUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-19 tras la aprobación: la entrega al vendedor abre la inspección de 24 h (A10), el vendedor reporta un problema
 * (A8, RF-053) o el barrido reembolsa al vencer la ventana, con reintentos de la misma clave (A9), sin duplicar y con
 * dos réplicas corriéndolo a la vez.
 */
class ReturnInspectionSweepTests extends ReturnsTestSupport {
    @Autowired ApplicationEventPublisher events;
    @Autowired ReturnSweepUseCase sweep;
    @Autowired SimulatedRefundGateway gateway;
    @Autowired TransactionTemplate tx;

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

    private void delivered(long id) {
        tx.executeWithoutResult(s -> events.publishEvent(new ReturnDeliveredToSeller(id, buyerId, 1L,
                LocalDateTime.now())));
    }

    private long inInspection() throws Exception {
        long id = approved();
        delivered(id);
        return id;
    }

    /** Vence la ventana de inspección de una devolución sin esperar 24 horas. */
    private void expireInspection(long id) {
        jdbc.update("UPDATE return_requests SET inspection_due_at = ?, next_action_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().minusMinutes(5), id);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM return_requests WHERE id = ?", String.class, id);
    }

    // ---------- Entrega al vendedor: En inspección ----------

    @Test
    void whenLogisticsDeliversTheGoodsTheReturnGoesToInspectionWith24HoursAndNotifiesBothSides() throws Exception {
        long id = approved();

        delivered(id);

        assertThat(statusOf(id)).isEqualTo("IN_INSPECTION");
        LocalDateTime started = jdbc.queryForObject("SELECT inspection_started_at FROM return_requests", LocalDateTime.class);
        assertThat(jdbc.queryForObject("SELECT inspection_due_at FROM return_requests", LocalDateTime.class))
                .isEqualTo(started.plusHours(24));
        assertThat(jdbc.queryForObject("SELECT next_action_at FROM return_requests", LocalDateTime.class))
                .isEqualTo(started.plusHours(24));
        assertThat(jdbc.queryForList("SELECT event_type FROM return_events WHERE event_type = 'INSPECTION_STARTED'",
                String.class)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT recipient_type FROM notifications WHERE type = 'RETURN_INSPECTION_STARTED' "
                + "ORDER BY recipient_type", String.class)).containsExactly("BUYER", "STORE");
        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.status", is("IN_INSPECTION")))
                .andExpect(jsonPath("$.inspectionDueAt").isNotEmpty());
    }

    @Test
    void aRepeatedOrIrrelevantDeliveryEventChangesNothingAndNeverThrows() throws Exception {
        long id = approved();
        delivered(id);
        int events = count("return_events");
        int notifications = count("notifications");

        delivered(id);

        assertThat(count("return_events")).isEqualTo(events);
        assertThat(count("notifications")).isEqualTo(notifications);
        DeliveredOrder other = deliveredOrder(buyerId, LocalDateTime.now().minusDays(1));
        long notApproved = idOf(requestReturn(buyer, other.orderId(), other.itemId(), "DEFECTIVE", "x")
                .andExpect(status().isCreated()), "id");
        delivered(notApproved);
        delivered(987654L);
        assertThat(statusOf(notApproved)).isEqualTo("REQUESTED");
    }

    // ---------- A8, RF-053: problema en la inspección ----------

    @Test
    void aProblemReportedInsideTheWindowOpensTheBuyersClaimKeepsTheStateAndStopsTheRefund() throws Exception {
        long id = inInspection();

        perform(seller, post(SELLER_RETURNS + "/" + id + "/report-problem").contentType("application/json")
                .content("{\"description\":\"Llegó roto\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_INSPECTION"))).andExpect(jsonPath("$.problemReported", is(true)))
                .andExpect(jsonPath("$.problemDescription", is("Llegó roto"))).andExpect(jsonPath("$.claimId").isNumber());

        assertThat(jdbc.queryForMap("SELECT buyer_account_id, description, product_id FROM claims"))
                .containsEntry("buyer_account_id", buyerId).containsEntry("description", "Llegó roto");
        assertThat(jdbc.queryForObject("SELECT claim_id FROM return_requests", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT id FROM claims", Long.class));
        assertThat(jdbc.queryForObject("SELECT next_action_at FROM return_requests", LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'RETURN_PROBLEM_REPORTED' AND "
                + "recipient_type = 'BUYER'", Integer.class)).isEqualTo(1);
        expireInspection(id);
        jdbc.update("UPDATE return_requests SET next_action_at = NULL WHERE id = ?", id);
        assertThat(sweep.runOnce().claimed()).isZero();
        assertThat(count("refunds")).isZero();
        perform(seller, post(SELLER_RETURNS + "/" + id + "/report-problem").contentType("application/json")
                .content("{\"description\":\"otra\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_PROBLEM_ALREADY_REPORTED")));
    }

    @Test
    void aProblemOutsideTheInspectionWindowOrOfAReturnNotInInspectionIsRefusedWithoutOpeningAClaim() throws Exception {
        long notYet = approved();
        perform(seller, post(SELLER_RETURNS + "/" + notYet + "/report-problem").contentType("application/json")
                .content("{\"description\":\"roto\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_INVALID_STATE")));

        long late = inInspection();
        expireInspection(late);
        perform(seller, post(SELLER_RETURNS + "/" + late + "/report-problem").contentType("application/json")
                .content("{\"description\":\"roto\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RETURN_INSPECTION_CLOSED")));
        assertThat(count("claims")).isZero();
    }

    @Test
    void aFailureAfterOpeningTheClaimRollsTheClaimBackToo() throws Exception {
        long id = inInspection();

        perform(seller, post(SELLER_RETURNS + "/" + id + "/report-problem").contentType("application/json")
                .content("{\"description\":\"   \"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("RETURN_PROBLEM_REQUIRED")));

        assertThat(count("claims")).isZero();
        assertThat(jdbc.queryForObject("SELECT problem_reported FROM return_requests", Boolean.class)).isFalse();
    }

    @Test
    void anAlreadyOpenClaimForTheProductBlocksTheReportAndLeavesTheReturnUntouched() throws Exception {
        long id = inInspection();
        long product = jdbc.queryForObject("SELECT product_id FROM return_requests", Long.class);
        long order = jdbc.queryForObject("SELECT order_id FROM return_requests", Long.class);
        jdbc.update("""
                INSERT INTO claims(order_id, product_id, product_name, item_total, buyer_account_id, store_id, description,
                    status, created_at, updated_at) VALUES (?, ?, 'Lámpara', 20.00, ?, 1, 'previa', 'OPEN', NOW(6), NOW(6))""",
                order, product, buyerId);

        perform(seller, post(SELLER_RETURNS + "/" + id + "/report-problem").contentType("application/json")
                .content("{\"description\":\"roto\"}")).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT problem_reported FROM return_requests", Boolean.class)).isFalse();
        assertThat(count("claims")).isEqualTo(1);
    }

    // ---------- Barrido: reembolso al vencer la ventana ----------

    @Test
    void whenTheWindowEndsWithoutAProblemTheSweepRefundsTheLineOnceAndFinishesTheReturn() throws Exception {
        long id = inInspection();
        assertThat(sweep.runOnce().claimed()).isZero(); // la ventana todavía está abierta
        expireInspection(id);

        ReturnSweepUseCase.Summary summary = sweep.runOnce();

        assertThat(summary).isEqualTo(new ReturnSweepUseCase.Summary(1, 1, 0));
        assertThat(statusOf(id)).isEqualTo("FINISHED");
        assertThat(jdbc.queryForMap("SELECT idempotency_key, status, order_id FROM refunds"))
                .containsEntry("idempotency_key", "return-" + id).containsEntry("status", "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT amount FROM refunds", BigDecimal.class)).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForList("SELECT event_type FROM return_events ORDER BY id", String.class)).containsExactly(
                "REQUESTED", "REVIEW_STARTED", "APPROVED", "INSPECTION_STARTED", "REFUND_REQUESTED", "FINISHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'RETURN_REFUND_COMPLETED' AND "
                + "recipient_type = 'BUYER'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_action_at FROM return_requests", LocalDateTime.class)).isNull();
        assertThat(sweep.runOnce().claimed()).isZero();
        assertThat(count("refunds")).isEqualTo(1);
        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.status", is("FINISHED")));
    }

    @Test
    void anUnavailablePaymentGatewayIsRetriedLaterWithTheSameKeyAndNeverDuplicatesTheRefund() throws Exception {
        long id = inInspection();
        expireInspection(id);
        gateway.setMode(SimulatedRefundGateway.Mode.UNAVAILABLE);

        assertThat(sweep.runOnce()).isEqualTo(new ReturnSweepUseCase.Summary(1, 0, 1));

        assertThat(statusOf(id)).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT refund_attempts FROM return_requests", Integer.class)).isEqualTo(1);
        LocalDateTime next = jdbc.queryForObject("SELECT next_action_at FROM return_requests", LocalDateTime.class);
        assertThat(next).isAfter(LocalDateTime.now().plusMinutes(10)).isBefore(LocalDateTime.now().plusMinutes(20));
        assertThat(jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("FAILED");
        assertThat(sweep.runOnce().claimed()).isZero(); // todavía no toca reintentar

        gateway.setMode(SimulatedRefundGateway.Mode.OK);
        jdbc.update("UPDATE return_requests SET next_action_at = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), id);
        assertThat(sweep.runOnce()).isEqualTo(new ReturnSweepUseCase.Summary(1, 1, 0));

        assertThat(statusOf(id)).isEqualTo("FINISHED");
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("COMPLETED");
    }

    @Test
    void aRefundStillPendingAtTheGatewayIsRetriedAndAfterTheMaximumItIsLeftForManualReview() throws Exception {
        long id = inInspection();
        expireInspection(id);
        gateway.setMode(SimulatedRefundGateway.Mode.PENDING);

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(sweep.runOnce().refundsNotCompleted()).isEqualTo(1);
            jdbc.update("UPDATE return_requests SET next_action_at = ? WHERE id = ? AND next_action_at IS NOT NULL",
                    LocalDateTime.now().minusMinutes(1), id);
        }

        assertThat(statusOf(id)).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT refund_attempts FROM return_requests", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT next_action_at FROM return_requests", LocalDateTime.class)).isNull();
        assertThat(jdbc.queryForList("SELECT event_type FROM return_events WHERE event_type LIKE 'REFUND_%' ORDER BY id",
                String.class)).containsExactly("REFUND_REQUESTED", "REFUND_RETRY_SCHEDULED", "REFUND_RETRY_SCHEDULED",
                "REFUND_RETRY_SCHEDULED", "REFUND_RETRY_SCHEDULED", "REFUND_GAVE_UP");
        assertThat(sweep.runOnce().claimed()).isZero();
        assertThat(count("refunds")).isEqualTo(1);
    }

    @Test
    void aRefundThatWouldExceedTheOrdersTotalIsNotSentAndCountsAsAnAttemptWithItsReason() throws Exception {
        long id = inInspection();
        long order = jdbc.queryForObject("SELECT order_id FROM return_requests", Long.class);
        jdbc.update("""
                INSERT INTO refunds(order_id, amount, idempotency_key, status, attempts, correlation_id, created_at,
                    updated_at) VALUES (?, 10.00, 'claim-99', 'COMPLETED', 1, 'x', NOW(6), NOW(6))""", order);
        expireInspection(id);

        assertThat(sweep.runOnce()).isEqualTo(new ReturnSweepUseCase.Summary(1, 0, 1));

        assertThat(statusOf(id)).isEqualTo("REFUND_PENDING");
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT details FROM return_events WHERE event_type = 'REFUND_RETRY_SCHEDULED'",
                String.class)).contains("supera lo pagado");
    }

    @Test
    void aClaimBornReturnRefundsTheAgreedAmountNotTheWholeLine() throws Exception {
        long id = inInspection();
        jdbc.update("UPDATE return_requests SET refund_amount = 12.50 WHERE id = ?", id);
        expireInspection(id);

        sweep.runOnce();

        assertThat(jdbc.queryForObject("SELECT amount FROM refunds", BigDecimal.class)).isEqualByComparingTo("12.50");
    }

    // ---------- Dos réplicas a la vez ----------

    @Test
    void twoSweepsAtTheSameTimeNeverProcessTheSameReturnTwice() throws Exception {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long id = inInspection();
            expireInspection(id);
            ids.add(id);
        }
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<CompletableFuture<ReturnSweepUseCase.Summary>> runs = List.of(1, 2).stream()
                .map(replica -> CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await();
                        return sweep.runOnce();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })).toList();

        List<ReturnSweepUseCase.Summary> summaries = runs.stream().map(CompletableFuture::join).toList();

        assertThat(summaries.stream().mapToInt(ReturnSweepUseCase.Summary::claimed).sum()).isEqualTo(4);
        assertThat(summaries.stream().mapToInt(ReturnSweepUseCase.Summary::refundsCompleted).sum()).isEqualTo(4);
        ids.forEach(id -> assertThat(statusOf(id)).isEqualTo("FINISHED"));
        assertThat(count("refunds")).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT COUNT(*) FROM return_events WHERE event_type = 'FINISHED' GROUP BY return_id",
                Integer.class)).containsOnly(1);
    }

    @Test
    void theBatchSizeLimitsWhatOneRoundTakes() throws Exception {
        for (int i = 0; i < 3; i++) {
            expireInspection(inInspection());
        }

        assertThat(sweep.runOnce().claimed()).isEqualTo(3); // el lote por defecto (20) alcanza
        assertThat(count("refunds")).isEqualTo(3);
    }
}
