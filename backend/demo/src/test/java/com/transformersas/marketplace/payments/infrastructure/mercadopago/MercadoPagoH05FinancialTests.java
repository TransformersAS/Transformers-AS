package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;

class MercadoPagoH05FinancialTests extends MercadoPagoH05TestSupport {
    @Autowired com.transformersas.marketplace.notifications.application.usecase.OrderNotificationPublisher notices;
    @Autowired MercadoPagoFinancialService financial;
    @Autowired MercadoPagoPaymentJournal journal;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.transformersas.marketplace.payments.domain.repository.RefundRepository localRefunds;
    private final Map<String,List<JsonNode>> externalRefunds = new ConcurrentHashMap<>();
    private final Map<String,String> keys = new ConcurrentHashMap<>();
    private final AtomicInteger posts = new AtomicInteger();
    private String mode = "confirmed";

    @BeforeEach void simulatedRefundApi() {
        externalRefunds.clear(); keys.clear(); posts.set(0); mode = "confirmed";
        when(provider.refunds(anyString())).thenAnswer(call -> externalRefunds.getOrDefault(call.getArgument(0), List.of()));
        when(provider.requestFullRefund(anyString(), anyString())).thenAnswer(call -> {
            String id = call.getArgument(0), key = call.getArgument(1);
            posts.incrementAndGet();
            String previous = keys.putIfAbsent(id, key);
            if (previous != null) assertThat(key).isEqualTo(previous);
            if (mode.equals("unknown")) throw new ResponseStatusException(BAD_GATEWAY, "resultado incierto");
            String state = mode.equals("pending") ? "in_process" : mode.equals("rejected") ? "rejected" : "approved";
            JsonNode refund = refund(id, "900" + id, payments.get(id).path("transaction_amount").decimalValue(), state);
            externalRefunds.put(id, List.of(refund));
            if (state.equals("approved")) refunded(id);
            if (mode.equals("timeout-after-processing")) throw new ResponseStatusException(BAD_GATEWAY, "respuesta perdida");
            return refund;
        });
    }
    private MercadoPagoFinancialService worker(int max) { return new MercadoPagoFinancialService(jdbc, provider, journal, transactions, max, 10, notices); }
    private JsonNode refund(String id, String refundId, java.math.BigDecimal amount, String state) {
        return json.valueToTree(Map.of("id", refundId, "payment_id", id, "amount", amount, "status", state));
    }
    private void refunded(String id) {
        var data = new HashMap<String,Object>(json.convertValue(payments.get(id), Map.class));
        data.put("status", "refunded"); data.put("transaction_amount_refunded", payments.get(id).path("transaction_amount").decimalValue());
        payments.put(id, json.valueToTree(data));
    }
    private MercadoPagoCheckoutService.CheckoutStatus unfulfilled() throws Exception {
        var attempt = start(); expire(); payment(attempt, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(checkout(attempt.id())).containsEntry("status", "REVIEW");
        return attempt;
    }
    private Map<String,Object> operation(String id) { return jdbc.queryForMap("SELECT * FROM external_payment_refunds WHERE payment_id=?", id); }
    private void due(String id) { jdbc.update("UPDATE external_payment_refunds SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE payment_id=?", id); }

    @Test void approvedPaymentWithoutOrderIsReturnedAndReviewClosedWithoutInventingOrderOrStock() throws Exception {
        var attempt = unfulfilled();
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(posts.get()).isEqualTo(1);
        assertThat(operation("456")).containsEntry("status", "CONFIRMED").containsEntry("externally_confirmed", true);
        assertThat(checkout(attempt.id())).containsEntry("status", "REFUNDED").containsEntry("order_id", null);
        assertThat(count("orders")).isZero(); assertThat(count("refunds")).isZero(); assertThat(stock()).isEqualTo(10);
        assertThat(count("external_payment_refunds")).isEqualTo(1);
        verify(provider, times(1)).create(anyString(), any(), anyString());
    }

    @Test void additionalPaymentIsReturnedWithoutCancellingOrRefundingTheOriginalOrder() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200);
        payment(attempt, "457", "approved"); assertThat(webhook("457")).isEqualTo(200);
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(operation("457")).containsEntry("status", "CONFIRMED").containsEntry("reason", "ADDITIONAL_PAYMENT");
        assertThat(keys).containsOnlyKeys("457");
        assertOnePurchase(attempt.id());
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, orderId(attempt.id()))).isEqualTo("APPROVED");
    }

    @Test void cancelledOrderIsAutomaticallyReturnedAndUpdatesExistingRefundWithoutRestoringStockAgain() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved"); assertThat(webhook("456")).isEqualTo(200);
        long id = orderId(attempt.id()); assertThat(cancel(id)).isEqualTo(200);
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status,payment_status FROM orders WHERE id=?", id))
                .containsEntry("status", "CANCELLED").containsEntry("payment_status", "REFUNDED");
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE order_id=?", String.class, id)).isEqualTo("COMPLETED");
        assertThat(count("refunds")).isEqualTo(1); assertThat(count("order_cancellations")).isEqualTo(1);
        assertThat(stock()).isEqualTo(10);
    }

    @Test void pendingRefundIsNotConfirmedAndPollingDoesNotSubmitAnotherRefund() throws Exception {
        var attempt = unfulfilled(); mode = "pending";
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(operation("456")).containsEntry("status", "PENDING").containsEntry("externally_confirmed", false);
        assertThat(checkout(attempt.id())).containsEntry("status", "REVIEW");
        due("456"); assertThat(worker(8).recoverBatch()).isEqualTo(1);
        assertThat(posts.get()).isEqualTo(1);
        refunded("456"); assertThat(webhook("456")).isEqualTo(200);
        assertThat(operation("456")).containsEntry("status", "CONFIRMED");
    }

    @Test void rejectedRefundRemainsFailedWithoutFakeConfirmationOrRepeatedSubmission() throws Exception {
        var attempt = unfulfilled(); mode = "rejected";
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(operation("456")).containsEntry("status", "FAILED").containsEntry("externally_confirmed", false);
        assertThat(checkout(attempt.id())).containsEntry("status", "REVIEW");
        assertThat(financial.recoverBatch()).isZero(); assertThat(posts.get()).isEqualTo(1);
    }

    @Test void timeoutAfterProviderProcessedRefundIsRecoveredByQueryWithoutSecondPost() throws Exception {
        unfulfilled(); mode = "timeout-after-processing";
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(operation("456")).containsEntry("status", "CONFIRMED").containsEntry("confirmation_source", "PAYMENT_STATUS");
        assertThat(posts.get()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT result FROM external_refund_events WHERE phase='RESPONSE'", String.class)).containsExactly("unknown");
        assertThat(worker(8).recoverBatch()).isZero();
    }

    @Test void ambiguousTimeoutSurvivesLogicalRestartAndNeverResubmitsWithNewKey() throws Exception {
        unfulfilled(); mode = "unknown";
        var first = worker(2); assertThat(first.recoverBatch()).isEqualTo(1);
        String key = (String) operation("456").get("idempotency_key");
        assertThat(operation("456")).containsEntry("status", "UNKNOWN").containsEntry("submission_started", true);
        due("456"); assertThat(worker(2).recoverBatch()).isEqualTo(1);
        assertThat(operation("456")).containsEntry("status", "EXCEPTION").containsEntry("last_error", "MAX_ATTEMPTS");
        assertThat(operation("456").get("idempotency_key")).isEqualTo(key);
        assertThat(posts.get()).isEqualTo(1);
        // Evidencia externa posterior permite conciliar sin otro POST, incluso tras agotar intentos.
        refunded("456"); assertThat(webhook("456")).isEqualTo(200);
        assertThat(operation("456")).containsEntry("status", "CONFIRMED");
    }

    @Test void twoWorkersSubmitOnlyOneRefund() throws Exception {
        unfulfilled();
        var first = worker(8); var second = worker(8);
        var results = together(first::recoverBatch, second::recoverBatch);
        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(results.stream().mapToInt(result -> (Integer) result.value()).sum()).isEqualTo(1);
        assertThat(posts.get()).isEqualTo(1); assertThat(count("external_payment_refunds")).isEqualTo(1);
        assertThat(operation("456")).containsEntry("status", "CONFIRMED");
    }

    @Test void repeatedWebhooksAfterRefundDoNotRecreateAnOrderOrSubmitAgain() throws Exception {
        var attempt = unfulfilled(); assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(webhook("456")).isEqualTo(200); assertThat(webhook("456")).isEqualTo(200);
        assertThat(financial.recoverBatch()).isZero();
        assertThat(posts.get()).isEqualTo(1); assertThat(count("orders")).isZero(); assertThat(stock()).isEqualTo(10);
        assertThat(checkout(attempt.id())).containsEntry("status", "REFUNDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_refund_events WHERE phase='CONFIRMATION'", Integer.class)).isEqualTo(1);
    }

    @Test void confirmedRefundListCanResolveEvenWhilePaymentStatusHasNotCaughtUp() throws Exception {
        var attempt = unfulfilled();
        externalRefunds.put("456", List.of(refund("456", "901", attempt.total(), "approved")));
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(posts.get()).isZero();
        assertThat(operation("456")).containsEntry("status", "CONFIRMED").containsEntry("confirmation_source", "REFUND_QUERY");
    }

    @Test void existingPartialRefundIsAnExceptionAndDoesNotTriggerFullRefundAutomatically() throws Exception {
        unfulfilled();
        externalRefunds.put("456", List.of(refund("456", "901", new java.math.BigDecimal("1000"), "approved")));
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(operation("456")).containsEntry("status", "EXCEPTION").containsEntry("last_error", "PARTIAL_REFUND_REQUIRES_REVIEW");
        assertThat(posts.get()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"id", "reference", "collector", "currency", "amount"})
    void everyIdentityFieldIsReverifiedBeforeMovingMoney(String field) throws Exception {
        var attempt = unfulfilled();
        var data = new HashMap<String,Object>(json.convertValue(payments.get("456"), Map.class));
        switch (field) {
            case "id" -> data.put("id", "999"); case "reference" -> data.put("external_reference", UUID.randomUUID().toString());
            case "collector" -> data.put("collector_id", "999"); case "currency" -> data.put("currency_id", "USD");
            case "amount" -> data.put("transaction_amount", attempt.total().subtract(java.math.BigDecimal.ONE));
        }
        payments.put("456", json.valueToTree(data));
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(posts.get()).isZero(); assertThat(operation("456").get("externally_confirmed")).isEqualTo(false);
        assertThat(stock()).isEqualTo(10); assertThat(count("orders")).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {1, 8})
    void refundBudgetIsReservedDuringUncertainRequestAndCannotBeReleasedByGuardFailure(int maxAttempts) throws Exception {
        var attempt = start(); payment(attempt, "456", "approved"); assertThat(webhook("456")).isEqualTo(200);
        long id = orderId(attempt.id()); assertThat(cancel(id)).isEqualTo(200);
        mode = "unknown"; assertThat(worker(maxAttempts).recoverBatch()).isEqualTo(1);
        long legacy = jdbc.queryForObject("SELECT id FROM refunds WHERE order_id=?", Long.class, id);
        localRefunds.markFailed(legacy, "guard concurrente");
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE id=?", String.class, legacy)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, id)).isEqualTo("REFUND_PENDING");
        assertThat(posts.get()).isEqualTo(1);
    }

    @Test void anotherLocalRefundPreventsExceedingOrderTotal() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved"); assertThat(webhook("456")).isEqualTo(200);
        long id = orderId(attempt.id()); assertThat(cancel(id)).isEqualTo(200);
        jdbc.update("""
                INSERT INTO refunds(order_id,amount,idempotency_key,status,correlation_id,created_at,updated_at)
                VALUES (?,1,'another-cause','COMPLETED','test',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, id);
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(posts.get()).isZero(); assertThat(operation("456")).containsEntry("status", "EXCEPTION").containsEntry("last_error", "ORDER_REFUND_LIMIT");
        assertThat(stock()).isEqualTo(10);
    }

    @Test void aNewApprovalAfterRefundedCheckoutIsReturnedRatherThanCreatingAnOrder() throws Exception {
        var attempt = unfulfilled(); assertThat(financial.recoverBatch()).isEqualTo(1);
        payment(attempt, "457", "approved"); assertThat(webhook("457")).isEqualTo(200);
        assertThat(checkout(attempt.id())).containsEntry("status", "REVIEW");
        assertThat(count("orders")).isZero();
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(posts.get()).isEqualTo(2); // Dos pagos diferentes, una devolución por cada uno.
        assertThat(keys).hasSize(2); assertThat(stock()).isEqualTo(10);
        assertThat(checkout(attempt.id())).containsEntry("status", "REFUNDED");
    }

    @Test void legacyCancelledOrderWithoutObservationIsDiscoveredUsingItsPersistedPaymentId() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved"); assertThat(webhook("456")).isEqualTo(200);
        long id = orderId(attempt.id()); assertThat(cancel(id)).isEqualTo(200);
        jdbc.update("DELETE FROM external_payment_observations WHERE payment_id='456'");
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(operation("456")).containsEntry("status", "CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, id)).isEqualTo("REFUNDED");
        assertThat(posts.get()).isEqualTo(1); assertThat(stock()).isEqualTo(10);
    }

    @Test void refundWorkerAndRepeatedApprovalWebhookCanOverlapWithoutReactivation() throws Exception {
        var attempt = unfulfilled();
        var results = together(financial::recoverBatch, () -> webhook("456"));
        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(results.get(1).value()).isEqualTo(200);
        assertThat(posts.get()).isEqualTo(1); assertThat(stock()).isEqualTo(10);
        assertThat(count("orders")).isZero(); assertThat(checkout(attempt.id())).containsEntry("status", "REFUNDED");
    }

    @Test void contradictoryPartialRefundDataDoesNotBecomeAFullFinancialConfirmation() throws Exception {
        var attempt = unfulfilled(); refunded("456");
        var data = new HashMap<String,Object>(json.convertValue(payments.get("456"), Map.class));
        data.put("transaction_amount_refunded", 1); payments.put("456", json.valueToTree(data));
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(checkout(attempt.id())).containsEntry("status", "REVIEW");
        financial.recoverBatch();
        assertThat(posts.get()).isZero(); assertThat(operation("456").get("externally_confirmed")).isEqualTo(false);
    }

    @Test void refundResponseForAnotherPaymentCannotConfirmOrTriggerMoneyMovement() throws Exception {
        var attempt = unfulfilled();
        externalRefunds.put("456", List.of(refund("999", "901", attempt.total(), "approved")));
        assertThat(financial.recoverBatch()).isEqualTo(1);
        assertThat(operation("456").get("externally_confirmed")).isEqualTo(false);
        assertThat(posts.get()).isZero();
    }

    @Test void staleWorkerCannotSubmitAfterLeaseWasReassigned() throws Exception {
        unfulfilled(); mode = "pending";
        financial.recoverBatch(); // Crear intención y solicitud pendiente.
        due("456");
        var first = worker(8); var second = worker(8);
        var old = first.claimNext();
        jdbc.update("UPDATE external_payment_refunds SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE payment_id='456'");
        var current = second.claimNext();
        first.process(old); second.process(current);
        assertThat(posts.get()).isEqualTo(1);
    }
}
