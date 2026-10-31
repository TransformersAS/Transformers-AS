package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contratos de seguridad y caracterización; no confunde quedar en REVIEW con recuperación financiera. */
class MercadoPagoH05SafetyTests extends MercadoPagoH05TestSupport {
    @Test void sameApprovedPaymentNotifiedTwiceCreatesOneOrder() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(webhook("456")).isEqualTo(200);
        assertOnePurchase(attempt.id());
        verify(provider, times(1)).create(anyString(), any(), anyString());
        observed("duplicated-webhook", checkout(attempt.id()).get("payment_id"));
    }

    @Test void repeatedApprovalAfterCancellationDoesNotReactivateOrConsumeStock() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        assertThat(browser(attempt.id(), "456")).isEqualTo(200);
        long id = orderId(attempt.id());
        assertThat(cancel(id)).isEqualTo(200);
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(stock()).isEqualTo(10);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_cancellations")).isEqualTo(1);
        assertThat(count("order_status_history")).isEqualTo(2);
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status,payment_status FROM orders WHERE id=?", id))
                .containsEntry("status", "CANCELLED").containsEntry("payment_status", "REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE order_id=?", String.class, id)).isEqualTo("FAILED");
        assertThat(cancel(id)).isEqualTo(409);
        assertThat(stock()).isEqualTo(10);
        observed("approval-after-order-cancel", "CANCELLED/REFUND_PENDING; refund=FAILED (guard MP)");
    }

    @Test void browserThenWebhookHasOneEffect() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        assertThat(browser(attempt.id(), "456")).isEqualTo(200);
        assertThat(webhook("456")).isEqualTo(200);
        assertOnePurchase(attempt.id());
        observed("browser-then-webhook", "one purchase");
    }

    @RepeatedTest(3) void concurrentWebhooksHaveOneEffect() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        var results = together(() -> webhook("456"), () -> webhook("456"));
        observed("concurrent-webhooks", results);
        assertThat(results).allSatisfy(result -> { assertThat(result.failure()).isNull(); assertThat(result.value()).isEqualTo(200); });
        assertOnePurchase(attempt.id());
    }

    @RepeatedTest(3) void concurrentBrowserAndWebhookHaveOneEffect() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        var results = together(() -> browser(attempt.id(), "456"), () -> webhook("456"));
        observed("concurrent-browser-webhook", results);
        assertThat(results).allSatisfy(result -> { assertThat(result.failure()).isNull(); assertThat(result.value()).isEqualTo(200); });
        assertOnePurchase(attempt.id());
    }

    @RepeatedTest(3) void concurrentStartsReturnTheSameCheckoutWithoutExtraReservations() throws Exception {
        var results = together(this::start, this::start);
        observed("concurrent-start", results);
        // Comprobar invariantes incluso si una petición falla: no esconder el fallo ni admitirlo como éxito.
        assertThat(count("payment_checkouts")).isEqualTo(1);
        assertThat(count("inventory_reservations")).isEqualTo(1);
        assertThat(stock()).isEqualTo(10);
        verify(provider, times(1)).create(anyString(), any(), anyString());
        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        var first = (MercadoPagoCheckoutService.CheckoutStatus) results.get(0).value();
        var second = (MercadoPagoCheckoutService.CheckoutStatus) results.get(1).value();
        assertThat(first.id()).isEqualTo(second.id());
    }

    @Test void replayAndCancellationOfExistingOrderCanOverlapWithoutRestoringTwice() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        assertThat(browser(attempt.id(), "456")).isEqualTo(200);
        long id = orderId(attempt.id());
        var results = together(() -> webhook("456"), () -> cancel(id));
        observed("concurrent-replay-cancel", results);
        assertThat(results).allSatisfy(result -> { assertThat(result.failure()).isNull(); assertThat(result.value()).isEqualTo(200); });
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, id)).isEqualTo("CANCELLED");
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_cancellations")).isEqualTo(1);
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(count("order_status_history")).isEqualTo(2);
        assertThat(stock()).isEqualTo(10);
    }

    @Test void pendingPaymentDoesNotCreateOrderOrConsumeStock() throws Exception {
        var attempt = start(); payment(attempt, "456", "pending");
        assertThat(browser(attempt.id(), "456")).isEqualTo(200);
        assertThat(checkout(attempt.id())).containsEntry("status", "PENDING").containsEntry("payment_id", "456").containsEntry("order_id", null);
        assertThat(stock()).isEqualTo(10);
        assertThat(count("orders")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservations", String.class)).isEqualTo("ACTIVE");
        observed("pending", "payment persisted; no order");
    }

    @Test void lateApprovalWithValidReservationsStillCompletesOnce() throws Exception {
        var attempt = start(); payment(attempt, "456", "pending");
        assertThat(webhook("456")).isEqualTo(200);
        payment(attempt, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200);
        assertOnePurchase(attempt.id());
        observed("late-valid-reservation", "APPROVED");
    }

    @Test void expiredReservationKeepsPaymentInReviewWithoutOverselling() throws Exception {
        var attempt = start(); expire(); payment(attempt, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(checkout(attempt.id())).containsEntry("status", "REVIEW").containsEntry("payment_id", "456").containsEntry("order_id", null);
        assertThat(stock()).isEqualTo(10);
        assertThat(count("orders")).isZero(); assertThat(count("refunds")).isZero();
        observed("late-expired-reservation", "REVIEW without order or refund (safety only, not resolution)");
    }

    @ParameterizedTest @ValueSource(strings = {"rejected", "cancelled"})
    void currentProviderApprovalAfterRejectionOrProviderCancellationIsAccepted(String prior) throws Exception {
        var attempt = start(); payment(attempt, "456", prior);
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(checkout(attempt.id())).containsEntry("status", "REJECTED");
        payment(attempt, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200);
        assertOnePurchase(attempt.id());
        observed("approval-after-" + prior, "latest verified approval accepted; no local checkout cancellation exists");
    }

    @Test void verificationConnectionFailureLeavesStateIntactAndRetryDoesNotCreateAnotherPreference() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Fallo de conexión simulado",
                new ResourceAccessException("conexión interrumpida")))
                .doAnswer(call -> payments.get("456")).when(provider).payment("456");
        assertThat(browser(attempt.id(), "456")).isEqualTo(502);
        assertThat(checkout(attempt.id())).containsEntry("status", "PENDING").containsEntry("payment_id", null).containsEntry("order_id", null);
        assertThat(stock()).isEqualTo(10); assertThat(count("orders")).isZero();
        assertThat(browser(attempt.id(), "456")).isEqualTo(200);
        assertOnePurchase(attempt.id());
        verify(provider, times(1)).create(anyString(), any(), anyString());
        observed("verification-failure-retry", "recovered by explicit retry, no new preference");
    }

    @Test void localSaveFailureRollsBackAndExplicitRetryRecoversWhileReservationIsValid() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        failNextOrderSave.set(true);
        assertThatThrownBy(() -> checkouts.reconcile(accountId, attempt.id(), "456"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(checkout(attempt.id())).containsEntry("status", "PENDING").containsEntry("payment_id", null).containsEntry("order_id", null);
        assertThat(stock()).isEqualTo(10); assertThat(count("orders")).isZero();
        assertThat(count("order_status_history")).isZero(); assertThat(count("cart_items")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservations", String.class)).isEqualTo("ACTIVE");
        assertThat(browser(attempt.id(), "456")).isEqualTo(200);
        assertOnePurchase(attempt.id());
        verify(provider, times(1)).create(anyString(), any(), anyString());
        observed("local-insert-failure-retry", "INSERT rolled back; same external payment recovered by explicit retry");
    }
}
