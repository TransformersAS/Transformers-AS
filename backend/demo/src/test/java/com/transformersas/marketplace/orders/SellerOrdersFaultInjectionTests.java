package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.springframework.test.util.AopTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RNF-015 y A8: un fallo inyectado a mitad de la transacción revierte por completo (estado, historial, auditoría y
 * notificación) y el pedido conserva su último estado válido; después el reintento funciona.
 */
class SellerOrdersFaultInjectionTests extends AbstractIntegrationTest {

    @MockitoSpyBean PublishNotificationUseCase notifications;
    @MockitoSpyBean AuditRecorder audit;

    /** El espía envuelve al destino del proxy transaccional: se stubbea el destino, no el proxy (que exigiría transacción). */
    private static <T> T target(T bean) {
        return AopTestUtils.getUltimateTargetObject(bean);
    }

    private Session seller;
    private long order;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM notifications");
        seller = sellerOfStore("seller@example.com", 1);
        order = seedOrder(1, "CONFIRMED", seedProduct(1, "Lámpara", 7, "100.00"), 1, "100.00");
    }

    @AfterEach
    void removeInjectedFailures() {
        reset(target(notifications), target(audit));
    }

    private void startPreparation() throws Exception {
        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/start-preparation"));
    }

    private void assertNothingChanged() {
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", Integer.class, order))
                .isEqualTo(1);
        assertThat(count("audit_events")).isZero();
        assertThat(count("notifications")).isZero();
    }

    @Test
    void rnf015_failureWhilePublishingTheNotificationRollsBackTheWholeChange() throws Exception {
        doThrow(new IllegalStateException("fallo inyectado")).when(target(notifications)).execute(any());

        assertThatThrownBy(this::startPreparation).hasRootCauseMessage("fallo inyectado");

        assertNothingChanged();
    }

    @Test
    void rnf015_failureWhileWritingTheAuditRollsBackTheStateChangeAndTheHistory() throws Exception {
        doThrow(new IllegalStateException("fallo inyectado")).when(target(audit))
                .record(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(this::startPreparation).hasRootCauseMessage("fallo inyectado");

        assertNothingChanged();
    }

    @Test
    void a8_afterAFailedAttemptTheRetrySucceedsExactlyOnce() throws Exception {
        doThrow(new IllegalStateException("fallo inyectado")).when(target(notifications)).execute(any());
        assertThatThrownBy(this::startPreparation).hasRootCauseMessage("fallo inyectado");
        reset(target(notifications));

        performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/start-preparation"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PREPARATION"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id = ?", Integer.class, order))
                .isEqualTo(2);
        assertThat(count("notifications")).isEqualTo(1);
    }
}
