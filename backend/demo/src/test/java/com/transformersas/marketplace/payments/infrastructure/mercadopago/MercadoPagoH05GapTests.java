package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Contratos funcionales aún no satisfechos. Deben quedar rojos si se pierde trazabilidad o una resolución externa
 * verificada no se refleja localmente. No se deshabilitan ni se invierten las expectativas para hacerlos pasar.
 */
class MercadoPagoH05GapTests extends MercadoPagoH05TestSupport {
    @Test void additionalApprovedPaymentMustNotDisappearFromDurablePaymentReferences() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200);
        payment(attempt, "457", "approved");
        assertThat(webhook("457")).isEqualTo(200);
        assertOnePurchase(attempt.id()); // No duplicar pedido/stock es necesario, pero no resuelve el segundo pago.
        var references = jdbc.queryForList("""
                SELECT payment_id FROM payment_checkouts WHERE id=?
                UNION ALL SELECT SUBSTRING(transaction_id,4) FROM orders WHERE id=?
                UNION ALL SELECT payment_id FROM external_payment_observations WHERE checkout_id=?
                """, String.class, attempt.id(), orderId(attempt.id()), attempt.id());
        observed("GAP-second-approved-payment", "verified=456,457; durable references=" + references);
        assertThat(references).as("Ambos pagos aprobados verificados deben conservar trazabilidad, no ignorar el segundo")
                .contains("456", "457");
    }

    @Test void verifiedExternalRefundMustResolveAReviewCheckout() throws Exception {
        var attempt = start(); expire(); payment(attempt, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(checkout(attempt.id())).containsEntry("status", "REVIEW").containsEntry("payment_id", "456");
        payment(attempt, "456", "refunded"); // Resolución externa segura: ya no se trata de decidir si cumplir o devolver.
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(stock()).isEqualTo(10); assertThat(count("orders")).isZero();
        var row = checkout(attempt.id());
        observed("GAP-review-after-verified-refund", "external=refunded; local=" + row.get("status") + "; payment=" + row.get("payment_id"));
        assertThat(row.get("status")).as("Un pago con devolución externa verificada no debe seguir pendiente de resolución en REVIEW")
                .isNotEqualTo("REVIEW");
    }

    @Test void verifiedPaymentReferenceMustSurviveAnOrderInsertFailure() {
        var attempt = start(); payment(attempt, "456", "approved");
        failNextOrderSave.set(true);
        assertThatThrownBy(() -> checkouts.reconcile(accountId, attempt.id(), "456"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("orders")).isZero(); assertThat(stock()).isEqualTo(10);
        var row = checkout(attempt.id());
        observed("GAP-payment-reference-after-rollback", "status=" + row.get("status") + "; payment=" + row.get("payment_id") + "; order=" + row.get("order_id"));
        assertThat(row.get("payment_id")).as("La aprobación verificada debe conservar una referencia durable para recuperación sin depender del navegador")
                .isEqualTo("456");
    }

    @Test void verifiedProviderRefundOfCancelledOrderMustUpdateItsFinancialState() throws Exception {
        var attempt = start(); payment(attempt, "456", "approved");
        assertThat(webhook("456")).isEqualTo(200);
        long id = orderId(attempt.id());
        assertThat(cancel(id)).isEqualTo(200);
        payment(attempt, "456", "refunded");
        assertThat(webhook("456")).isEqualTo(200);
        assertThat(stock()).isEqualTo(10); assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_cancellations")).isEqualTo(1); assertThat(count("refunds")).isEqualTo(1);
        var order = jdbc.queryForMap("SELECT status,payment_status FROM orders WHERE id=?", id);
        var refund = jdbc.queryForMap("SELECT status,attempts FROM refunds WHERE order_id=?", id);
        observed("GAP-cancelled-order-verified-refund", "external=refunded; order=" + order + "; refund=" + refund);
        assertThat(order).containsEntry("status", "CANCELLED");
        assertSoftly(soft -> {
            soft.assertThat(order.get("payment_status")).as("Estado financiero tras devolución externa verificada").isEqualTo("REFUNDED");
            soft.assertThat(refund.get("status")).as("Solicitud de devolución reconciliada").isEqualTo("COMPLETED");
        });
    }
}
