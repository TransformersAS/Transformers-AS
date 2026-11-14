package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;

class MercadoPagoH05RecoveryTests extends MercadoPagoH05TestSupport {
    @Autowired MercadoPagoRecoveryService recovery;
    @Autowired MercadoPagoPaymentJournal journal;
    @Autowired PlatformTransactionManager transactions;

    private MercadoPagoRecoveryService worker(int max) {
        return new MercadoPagoRecoveryService(jdbc, provider, checkouts, journal, transactions, max, 10);
    }
    private void due(String id) {
        jdbc.update("UPDATE payment_checkouts SET recovery_next_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", id);
    }
    private void searchable(String checkout, List<String> ids) {
        when(provider.searchPayments(checkout, 0)).thenReturn(new MercadoPagoClient.PaymentSearch(ids, false));
    }

    @Test void duplicateObservationsKeepOnePaymentAndCountVerifications() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200); assertThat(webhook("456")).isEqualTo(200);
        assertOnePurchase(attempt.id());
        assertThat(count("external_payment_observations")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT verification_count,approved_seen,resolution_status FROM external_payment_observations WHERE payment_id='456'"))
                .containsEntry("verification_count", 2).containsEntry("approved_seen", true).containsEntry("resolution_status", "ORDER_LINKED");
    }

    @Test void providerQueriesRunOutsideDatabaseTransactions() {
        var attempt = start(); payment(attempt, "456", "approved");
        when(provider.searchPayments(attempt.id(), 0)).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new MercadoPagoClient.PaymentSearch(List.of("456"), false);
        });
        when(provider.payment("456")).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return payments.get("456");
        });
        assertThat(recovery.recoverBatch()).isEqualTo(1);
        assertOnePurchase(attempt.id());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"amount", "currency"})
    void verifiedPaymentWithDifferentAmountOrCurrencyIsRecordedAsAnIncidentWithoutFulfilling(String difference) throws Exception {
        var attempt = start();
        payments.put("456", json.valueToTree(Map.of("id", "456", "external_reference", attempt.id(),
                "collector_id", 123, "currency_id", difference.equals("currency") ? "USD" : "COP",
                "transaction_amount", difference.equals("amount") ? attempt.total().subtract(java.math.BigDecimal.ONE) : attempt.total(),
                "status", "approved", "live_mode", false)));
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(count("orders")).isZero(); assertThat(stock()).isEqualTo(10); assertThat(count("refunds")).isZero();
        assertThat(checkout(attempt.id())).containsEntry("status", "REVIEW");
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM external_payment_observations WHERE payment_id='456'", String.class))
                .isEqualTo("PAYMENT_MISMATCH");
    }

    @Test void searchByPersistedReferenceRecoversWithoutBrowserOrWebhook() {
        var attempt = start(); payment(attempt, "456", "approved"); searchable(attempt.id(), List.of("456"));
        assertThat(checkout(attempt.id()).get("payment_id")).isNull();
        assertThat(recovery.recoverBatch()).isEqualTo(1);
        assertOnePurchase(attempt.id());
        verify(provider).searchPayments(attempt.id(), 0);
        verify(provider, times(1)).create(anyString(), any(), anyString());
        assertThat(jdbc.queryForMap("SELECT verified_status,resolution_status,order_id FROM external_payment_observations WHERE payment_id='456'"))
                .containsEntry("verified_status", "approved").containsEntry("resolution_status", "ORDER_LINKED")
                .containsEntry("order_id", orderId(attempt.id()));
    }

    @Test void verifiedPaymentSurvivesRollbackAndFreshWorkerRecoversItFromDatabase() {
        var attempt = start(); payment(attempt, "456", "approved");
        failNextOrderSave.set(true);
        assertThatThrownBy(() -> checkouts.reconcile(accountId, attempt.id(), "456")).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("orders")).isZero(); assertThat(stock()).isEqualTo(10);
        assertThat(checkout(attempt.id())).containsEntry("payment_id", "456").containsEntry("order_id", null);
        assertThat(jdbc.queryForMap("SELECT verified_status,resolution_status,last_error FROM external_payment_observations WHERE payment_id='456'"))
                .containsEntry("verified_status", "approved").containsEntry("resolution_status", "PENDING_LOCAL")
                .containsEntry("last_error", "LOCAL_COMMIT_FAILED");
        // Otro orquestador, sin IDs de pago pasados desde memoria/browser; la pasarela simulada conserva su estado externo.
        assertThat(worker(8).recoverBatch()).isEqualTo(1);
        assertOnePurchase(attempt.id());
        assertThat(worker(8).recoverBatch()).isZero();
        verify(provider, times(1)).create(anyString(), any(), anyString());
    }

    @Test void twoWorkersCannotConsumeTheSamePaymentTwice() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved"); searchable(attempt.id(), List.of("456"));
        var first = worker(8); var second = worker(8);
        var results = together(first::recoverBatch, second::recoverBatch);
        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(results.stream().mapToInt(result -> (Integer) result.value()).sum()).isEqualTo(1);
        assertOnePurchase(attempt.id());
        assertThat(count("payment_recovery_attempts")).isEqualTo(1);
        verify(provider, times(1)).payment("456");
    }

    @Test void expiredLeaseIsReclaimedAndStaleWorkerCannotCommit() {
        var attempt = start(); payment(attempt, "456", "approved"); searchable(attempt.id(), List.of("456"));
        var first = worker(8); var second = worker(8);
        var old = first.claimNext();
        assertThat(second.claimNext()).isNull();
        jdbc.update("UPDATE payment_checkouts SET recovery_lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", attempt.id());
        var current = second.claimNext();
        assertThat(current).isNotNull();
        first.recover(old);
        assertThat(count("orders")).isZero(); assertThat(count("external_payment_observations")).isZero();
        assertThat(checkout(attempt.id())).containsEntry("recovery_lease_token", current.token());
        second.recover(current);
        assertOnePurchase(attempt.id());
        assertThat(jdbc.queryForList("SELECT result FROM payment_recovery_attempts ORDER BY id", String.class))
                .containsExactly("LEASE_EXPIRED", "ORDER_LINKED");
    }

    @Test void unavailableProviderIsRetriedWithBackoffAndFiniteAttemptBudget() {
        var attempt = start();
        when(provider.searchPayments(attempt.id(), 0)).thenThrow(new ResponseStatusException(BAD_GATEWAY, "simulated"));
        var worker = worker(2);
        assertThat(worker.recoverBatch()).isEqualTo(1);
        assertThat(checkout(attempt.id())).containsEntry("recovery_result", "RETRYABLE_FAILURE")
                .containsEntry("recovery_last_error", "PROVIDER_OR_RECOVERY_HTTP_502");
        assertThat(worker.recoverBatch()).isZero(); // Respeta next_at, no bucle inmediato.
        due(attempt.id());
        assertThat(worker.recoverBatch()).isEqualTo(1);
        due(attempt.id());
        assertThat(worker.recoverBatch()).isZero();
        assertThat(checkout(attempt.id())).containsEntry("recovery_result", "EXHAUSTED").containsEntry("recovery_attempts", 2);
        assertThat(jdbc.queryForList("SELECT result FROM payment_recovery_attempts ORDER BY id", String.class))
                .containsExactly("RETRYABLE_FAILURE", "EXHAUSTED");
        assertThat(stock()).isEqualTo(10); assertThat(count("orders")).isZero();
    }

    @Test void recoveryRetriesAProviderFailureWithoutCreatingANewPreference() {
        var attempt = start(); payment(attempt, "456", "approved"); searchable(attempt.id(), List.of("456"));
        doThrow(new ResponseStatusException(BAD_GATEWAY)).doAnswer(call -> payments.get("456")).when(provider).payment("456");
        assertThat(recovery.recoverBatch()).isEqualTo(1);
        assertThat(count("orders")).isZero();
        due(attempt.id());
        assertThat(worker(8).recoverBatch()).isEqualTo(1);
        assertOnePurchase(attempt.id());
        assertThat(count("payment_recovery_attempts")).isEqualTo(2);
        verify(provider, times(1)).create(anyString(), any(), anyString());
    }

    @Test void expiredReservationRemainsAnUnresolvedFinancialCaseWithoutRefundingOrOverselling() {
        var attempt = start(); expire(); payment(attempt, "456", "approved"); searchable(attempt.id(), List.of("456"));
        assertThat(recovery.recoverBatch()).isEqualTo(1);
        assertThat(checkout(attempt.id())).containsEntry("status", "REVIEW").containsEntry("recovery_result", "REQUIRES_REVIEW");
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM external_payment_observations WHERE payment_id='456'", String.class))
                .isEqualTo("REQUIRES_REVIEW");
        assertThat(count("orders")).isZero(); assertThat(count("refunds")).isZero(); assertThat(stock()).isEqualTo(10);
        due(attempt.id()); assertThat(recovery.recoverBatch()).isZero();
    }

    @Test void legacyCheckoutWithPaymentReferenceButNoJournalIsVerifiedLazily() {
        var attempt = start(); payment(attempt, "456", "approved");
        jdbc.update("UPDATE payment_checkouts SET payment_id='456',snapshot_json=JSON_REMOVE(snapshot_json,'$.discountAmount','$.shippingCost','$.sellerNameSnapshot') WHERE id=?", attempt.id());
        assertThat(count("external_payment_observations")).isZero();
        assertThat(recovery.recoverBatch()).isEqualTo(1);
        assertOnePurchase(attempt.id());
        var row = jdbc.queryForMap("SELECT discount_amount,shipping_cost,seller_name_snapshot FROM orders WHERE id=?", orderId(attempt.id()));
        assertThat(row.values()).containsOnlyNulls();
    }

    @Test void multipleVerifiedPaymentsAreJournaledWithoutDuplicatingOrder() {
        var attempt = start(); payment(attempt, "456", "approved"); payment(attempt, "457", "approved");
        searchable(attempt.id(), List.of("456", "457"));
        assertThat(recovery.recoverBatch()).isEqualTo(1);
        assertOnePurchase(attempt.id());
        assertThat(count("external_payment_observations")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM external_payment_observations WHERE payment_id='457'", String.class))
                .isEqualTo("ADDITIONAL_PAYMENT");
        assertThat(count("refunds")).isZero();
    }

    @Test void searchPaginationContinuesAfterFirstOrderWasCreated() {
        var attempt = start();
        var ids = IntStream.rangeClosed(456, 476).mapToObj(Integer::toString).toList();
        ids.forEach(id -> payment(attempt, id, "approved"));
        when(provider.searchPayments(attempt.id(), 0)).thenReturn(new MercadoPagoClient.PaymentSearch(ids.subList(0, 20), true));
        when(provider.searchPayments(attempt.id(), 20)).thenReturn(new MercadoPagoClient.PaymentSearch(ids.subList(20, 21), false));
        assertThat(recovery.recoverBatch()).isEqualTo(1);
        assertThat(checkout(attempt.id())).containsEntry("recovery_result", "MORE_RESULTS").containsEntry("recovery_offset", 20);
        due(attempt.id());
        assertThat(recovery.recoverBatch()).isEqualTo(1);
        assertOnePurchase(attempt.id());
        assertThat(count("external_payment_observations")).isEqualTo(21);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM external_payment_observations WHERE resolution_status='ADDITIONAL_PAYMENT'", Integer.class)).isEqualTo(20);
    }

    @Test void aSearchResultWithForeignReferenceCannotCreateOrRegisterAPaymentForThisCheckout() {
        var attempt = start(); payment(attempt, "456", "approved");
        payments.put("456", json.valueToTree(Map.of("id", "456", "external_reference", UUID.randomUUID().toString(),
                "collector_id", 123, "currency_id", "COP", "transaction_amount", attempt.total(), "status", "approved", "live_mode", false)));
        searchable(attempt.id(), List.of("456"));
        assertThat(recovery.recoverBatch()).isEqualTo(1);
        assertThat(count("orders")).isZero(); assertThat(count("external_payment_observations")).isZero();
        assertThat(stock()).isEqualTo(10);
        assertThat(checkout(attempt.id())).containsEntry("recovery_result", "RETRYABLE_FAILURE");
    }
}
