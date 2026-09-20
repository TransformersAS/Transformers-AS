package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.inventory.application.usecase.RestoreStockUseCase;
import com.transformersas.marketplace.payments.application.usecase.RequestRefundUseCase;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RNF-015: un fallo inyectado en cualquier punto de la transacción de cancelación revierte por completo (estado, cancelación,
 * stock, reembolso, historial, auditoría y notificaciones); un fallo posterior al commit no revierte nada.
 */
class SellerCancelFaultInjectionTests extends AbstractIntegrationTest {

    @MockitoSpyBean RestoreStockUseCase restoreStock;
    @MockitoSpyBean RequestRefundUseCase refunds;

    private Session seller;
    private long product;
    private long other;
    private long order;

    /** El espía envuelve al destino del proxy transaccional: se stubbea el destino, no el proxy. */
    private static <T> T target(T bean) {
        return AopTestUtils.getUltimateTargetObject(bean);
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM notifications");
        seller = sellerOfStore("seller@example.com", 1);
        product = seedProduct(1, "Lámpara", 5, "100.00");
        other = seedProduct(1, "Mesa", 10, "50.00");
        order = seedOrder(1, "IN_PREPARATION", product, 2, "100.00");
        jdbc.update("INSERT INTO order_items(order_id, product_id, product_name, quantity, unit_price, subtotal) "
                + "VALUES (?, ?, 'Mesa', 3, 50.00, 150.00)", order, other);
    }

    @AfterEach
    void removeInjectedFailures() {
        reset(target(restoreStock), target(refunds));
    }

    private void cancel() throws Exception {
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/cancel").contentType("application/json")
                .content("{\"reasonCode\":\"OUT_OF_STOCK\"}"));
    }

    private void assertNothingChanged() {
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order)).isEqualTo("IN_PREPARATION");
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id = ?", String.class, order)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, product)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, other)).isEqualTo(10);
        assertThat(count("order_cancellations")).isZero();
        assertThat(count("refunds")).isZero();
        assertThat(count("order_status_history")).isEqualTo(1);
        assertThat(count("audit_events")).isZero();
        assertThat(count("notifications")).isZero();
    }

    @Test
    void rnf015_aFailureRestoringTheSecondLineRevertsTheFirstLinesStockToo() throws Exception {
        doCallRealMethod().doThrow(new IllegalStateException("fallo inyectado"))
                .when(target(restoreStock)).execute(any(), anyInt());

        assertThatThrownBy(this::cancel).hasRootCauseMessage("fallo inyectado");

        assertNothingChanged(); // ni siquiera la primera reposición sobrevive
    }

    @Test
    void rnf015_aFailureRegisteringTheRefundRevertsTheStockRestorationAndTheStateChange() throws Exception {
        doThrow(new IllegalStateException("fallo inyectado")).when(target(refunds)).registerPending(any());

        assertThatThrownBy(this::cancel).hasRootCauseMessage("fallo inyectado");

        assertNothingChanged();
    }

    @Test
    void rnf015_afterAFailedAttemptTheRetrySucceedsWithAConsistentState() throws Exception {
        doThrow(new IllegalStateException("fallo inyectado")).when(target(refunds)).registerPending(any());
        assertThatThrownBy(this::cancel).hasRootCauseMessage("fallo inyectado");
        reset(target(refunds));

        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/cancel").contentType("application/json")
                .content("{\"reasonCode\":\"OUT_OF_STOCK\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.status").value("COMPLETED"));

        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, product)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, other)).isEqualTo(13);
        assertThat(count("order_cancellations")).isEqualTo(1);
        assertThat(count("refunds")).isEqualTo(1);
    }

    @Test
    void rnf045_aFailureProcessingTheRefundAfterTheCommitDoesNotRevertTheCancellation() throws Exception {
        doThrow(new IllegalStateException("fallo inyectado")).when(target(refunds)).execute(any());

        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/cancel").contentType("application/json")
                .content("{\"reasonCode\":\"OUT_OF_STOCK\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.refund.status").value("FAILED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUND_PENDING"));

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, product)).isEqualTo(7);
        // La solicitud de reembolso quedó registrada PENDING dentro de la transacción de cancelación.
        assertThat(jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("PENDING");
        assertThat(count("notifications")).isEqualTo(2);
    }
}
