package com.transformersas.marketplace.returns;

import com.transformersas.marketplace.logistics.application.dto.ReturnDeliveredToSeller;
import com.transformersas.marketplace.returns.application.event.ClaimResolvedRequiringReturn;
import com.transformersas.marketplace.returns.application.usecase.ReturnSweepUseCase;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El lado de devoluciones de una reclamación que exige devolver el producto (CU-13, D7/b): el oyente del evento crea la
 * devolución Aprobada, sin plazo y con la referencia, o reabre la que el vendedor había rechazado. El evento se publica a
 * mano porque reclamaciones todavía no lo publica (pendiente de la marca requiresReturn).
 */
class OpenReturnFromClaimTests extends ReturnsTestSupport {
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;
    @Autowired ReturnSweepUseCase sweep;

    private Session buyer;
    private Session seller;
    private long buyerId;

    @BeforeEach
    void seed() throws Exception {
        seller = sellerOfStore("vendedor@example.com", 1);
        buyer = sessionWithRole("comprador@example.com", "COMPRADOR");
        buyerId = accountIdOf("comprador@example.com");
    }

    private void publish(DeliveredOrder order, long claimId, BigDecimal agreed) {
        tx.executeWithoutResult(s -> events.publishEvent(new ClaimResolvedRequiringReturn(claimId, order.orderId(),
                order.productId(), buyerId, "Devolver por la reclamación", agreed)));
    }

    private DeliveredOrder delivered() {
        return deliveredOrder(buyerId, LocalDateTime.now().minusDays(3));
    }

    private long rejectedReturn(DeliveredOrder order) throws Exception {
        long id = idOf(requestReturn(buyer, order.orderId(), order.itemId(), "DEFECTIVE", "No enciende")
                .andExpect(status().isCreated()), "id");
        perform(seller, post(SELLER_RETURNS + "/" + id + "/reject").contentType("application/json")
                .content("{\"note\":\"El producto fue usado\"}")).andExpect(status().isOk());
        return id;
    }

    @Test
    void aClaimForALineWithoutAReturnCreatesItApprovedWithoutWindowAndWithTheAgreedRefund() throws Exception {
        DeliveredOrder order = delivered();

        publish(order, 44L, new BigDecimal("12.50"));

        assertThat(jdbc.queryForMap("SELECT status, origin, origin_claim_id, return_window_days, delivered_at "
                + "FROM return_requests")).containsEntry("status", "APPROVED").containsEntry("origin", "CLAIM")
                .containsEntry("origin_claim_id", 44L).containsEntry("return_window_days", null);
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM return_requests", BigDecimal.class))
                .isEqualByComparingTo("12.50");
        assertThat(jdbc.queryForList("SELECT event_type FROM return_events", String.class))
                .containsExactly("APPROVED_FROM_CLAIM");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'RETURN_APPROVED_FROM_CLAIM' AND "
                + "recipient_type = 'BUYER' AND recipient_id = ?", Integer.class, buyerId)).isEqualTo(1);
        long id = jdbc.queryForObject("SELECT id FROM return_requests", Long.class);
        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.origin", is("CLAIM"))).andExpect(jsonPath("$.originClaimId", is(44)))
                .andExpect(jsonPath("$.returnWindowEndsAt").doesNotExist());
        perform(seller, get(SELLER_RETURNS + "/" + id)).andExpect(jsonPath("$.status", is("APPROVED")));
    }

    @Test
    void aRejectedReturnIsReopenedByTheClaimKeepingItsHistoryAndRecordingTheOriginChange() throws Exception {
        DeliveredOrder order = delivered();
        long id = rejectedReturn(order);

        publish(order, 45L, null);

        assertThat(count("return_requests")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status, origin, origin_claim_id FROM return_requests WHERE id = ?", id))
                .containsEntry("status", "APPROVED").containsEntry("origin", "CLAIM")
                .containsEntry("origin_claim_id", 45L);
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM return_requests", BigDecimal.class))
                .isEqualByComparingTo("20.00");
        perform(buyer, get(RETURNS + "/" + id)).andExpect(jsonPath("$.timeline[*].type", contains("REQUESTED", "REJECTED",
                "REOPENED_FROM_CLAIM", "ORIGIN_CHANGED"))).andExpect(jsonPath("$.timeline[2].from", is("REJECTED")))
                .andExpect(jsonPath("$.timeline[2].to", is("APPROVED"))).andExpect(jsonPath("$.timeline[2].actor", is("SYSTEM")))
                .andExpect(jsonPath("$.timeline[3].details").value(org.hamcrest.Matchers.containsString("CLAIM")))
                .andExpect(jsonPath("$.decision.note").value(org.hamcrest.Matchers.containsString("45")));
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE entity_type = 'RETURN' ORDER BY id",
                String.class)).containsSubsequence("RETURN_REJECTED", "RETURN_REOPENED_FROM_CLAIM", "RETURN_ORIGIN_CHANGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'RETURN_APPROVED_FROM_CLAIM'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void theSameEventTwiceChangesNothingTheSecondTime() throws Exception {
        DeliveredOrder order = delivered();
        publish(order, 44L, null);
        int events = count("return_events");
        int notifications = count("notifications");

        publish(order, 44L, null);

        assertThat(count("return_requests")).isEqualTo(1);
        assertThat(count("return_events")).isEqualTo(events);
        assertThat(count("notifications")).isEqualTo(notifications);
        DeliveredOrder rejectedOrder = delivered();
        rejectedReturn(rejectedOrder);
        publish(rejectedOrder, 46L, null);
        int afterReopen = count("return_events");
        publish(rejectedOrder, 46L, null);
        assertThat(count("return_events")).isEqualTo(afterReopen);
    }

    @Test
    void onlyARejectedReturnCanBeReopenedAnyOtherExistingOneIsAConflictAndStaysUntouched() throws Exception {
        DeliveredOrder requested = delivered();
        requestReturn(buyer, requested.orderId(), requested.itemId(), "DEFECTIVE", "x").andExpect(status().isCreated());
        DeliveredOrder bornFromAnother = delivered();
        publish(bornFromAnother, 50L, null);

        assertThatThrownBy(() -> publish(requested, 44L, null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo("RETURN_ALREADY_EXISTS"));
        assertThatThrownBy(() -> publish(bornFromAnother, 51L, null)).isInstanceOf(BusinessException.class);

        assertThat(jdbc.queryForList("SELECT status FROM return_requests ORDER BY id", String.class))
                .containsExactly("REQUESTED", "APPROVED");
        assertThat(jdbc.queryForObject("SELECT origin_claim_id FROM return_requests WHERE status = 'APPROVED'", Long.class))
                .isEqualTo(50L);
    }

    @Test
    void anUnknownProductAnotherBuyersOrderAndAnInvalidRefundAreRefused() throws Exception {
        DeliveredOrder order = delivered();
        long stranger = createAccount("otra@example.com", "COMPRADOR");
        DeliveredOrder theirs = deliveredOrder(stranger, LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> events.publishEvent(new ClaimResolvedRequiringReturn(1L,
                order.orderId(), order.productId() + 999, buyerId, "x", null))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo("RETURN_LINE_NOT_IN_ORDER"));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> events.publishEvent(new ClaimResolvedRequiringReturn(1L,
                theirs.orderId(), theirs.productId(), buyerId, "x", null))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo("RETURN_ORDER_NOT_FOUND"));
        assertThatThrownBy(() -> publish(order, 1L, new BigDecimal("20.01")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo("RETURN_REFUND_AMOUNT_INVALID"));
        assertThat(count("return_requests")).isZero();
    }

    @Test
    void whatIsDoneInsideTheClaimsTransactionRollsBackWithIt() {
        DeliveredOrder order = delivered();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            events.publishEvent(new ClaimResolvedRequiringReturn(44L, order.orderId(), order.productId(), buyerId,
                    "Devolver", null));
            throw new IllegalStateException("falla la reclamación después");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("return_requests")).isZero();
        assertThat(count("return_events")).isZero();
        assertThat(count("notifications")).isZero();
    }

    @Test
    void aReopenedReturnGoesThroughInspectionAndRefundsTheAgreedAmountWithItsOwnKey() throws Exception {
        DeliveredOrder order = delivered();
        long id = rejectedReturn(order);
        publish(order, 45L, new BigDecimal("15.00"));

        tx.executeWithoutResult(s -> events.publishEvent(new ReturnDeliveredToSeller(id, buyerId, 1L, LocalDateTime.now())));
        jdbc.update("UPDATE return_requests SET inspection_due_at = ?, next_action_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().minusMinutes(1), id);
        sweep.runOnce();

        assertThat(jdbc.queryForObject("SELECT status FROM return_requests", String.class)).isEqualTo("FINISHED");
        assertThat(jdbc.queryForMap("SELECT idempotency_key, amount FROM refunds"))
                .containsEntry("idempotency_key", "return-" + id);
        assertThat(jdbc.queryForObject("SELECT amount FROM refunds", BigDecimal.class)).isEqualByComparingTo("15.00");
    }
}
