package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.application.dto.RegisterReturnShipmentCommand;
import com.transformersas.marketplace.logistics.application.usecase.RegisterReturnShipmentUseCase;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.support.AbstractTrackingTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RNF-015 en el seguimiento logístico: un fallo inyectado a mitad de la transacción revierte por completo (estado,
 * línea de tiempo, historial, auditoría y notificaciones), el pedido o la devolución conserva su último estado válido
 * y el reintento del proveedor funciona una sola vez.
 */
class TrackingFaultInjectionTests extends AbstractTrackingTest {

    @MockitoSpyBean PublishNotificationUseCase notifications;
    @MockitoSpyBean AuditRecorder audit;
    @Autowired RegisterReturnShipmentUseCase register;

    /** El espía envuelve al destino del proxy transaccional: se stubbea el destino, no el proxy. */
    private static <T> T target(T bean) {
        return AopTestUtils.getUltimateTargetObject(bean);
    }

    private long order;

    @BeforeEach
    void setUp() {
        Long buyerId = createAccount("buyer@example.com", "COMPRADOR");
        order = shippedOrder(buyerId, "READY_FOR_DISPATCH");
        register.execute(new RegisterReturnShipmentCommand(601L, buyerId, 1L, "SIM-return-601", "TRK-R601"));
    }

    @AfterEach
    void removeInjectedFailures() {
        reset(target(notifications), target(audit));
    }

    private void assertOrderUntouched() {
        assertThat(orderStatus(order)).isEqualTo("READY_FOR_DISPATCH");
        assertThat(count("shipment_tracking_events")).isZero();
        assertThat(historyStatuses(order)).containsExactly("READY_FOR_DISPATCH");
        assertThat(count("notifications")).isZero();
        assertThat(jdbc.queryForObject("SELECT tracking_active FROM shipments WHERE order_id = ?", Boolean.class, order))
                .isTrue();
    }

    @Test
    void rnf015_failureWhilePublishingTheNotificationRollsBackTheWholeShipmentUpdate() throws Exception {
        int audits = count("audit_events");
        doThrow(new IllegalStateException("fallo inyectado")).when(target(notifications)).execute(any());

        assertThatThrownBy(() -> sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(5)))
                .hasRootCauseMessage("fallo inyectado");

        assertOrderUntouched();
        assertThat(count("audit_events")).isEqualTo(audits);

        // El proveedor reenvía el mismo evento: ahora se procesa completo y una sola vez.
        reset(target(notifications));
        sendShipmentEvent(order, "evt-1", "PICKED_UP", secondsAgo(5)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));
        assertThat(orderStatus(order)).isEqualTo("PICKED_UP");
        assertThat(orderEventOutcomes(order)).containsExactly("PICKED_UP:APPLIED");
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void rnf015_failureWhileWritingTheAuditRollsBackTheStateChangeTheHistoryAndTheEvent() throws Exception {
        int audits = count("audit_events");
        doThrow(new IllegalStateException("fallo inyectado")).when(target(audit))
                .record(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> sendShipmentEvent(order, "evt-1", "IN_TRANSIT", secondsAgo(5)))
                .hasRootCauseMessage("fallo inyectado");

        assertOrderUntouched();
        assertThat(count("audit_events")).isEqualTo(audits);
    }

    @Test
    void rnf015_failureWhilePublishingTheNotificationRollsBackTheWholeReturnUpdate() throws Exception {
        doThrow(new IllegalStateException("fallo inyectado")).when(target(notifications)).execute(any());

        assertThatThrownBy(() -> sendReturnEvent(601, "evt-1", "PICKED_UP", secondsAgo(5)))
                .hasRootCauseMessage("fallo inyectado");

        assertThat(returnStatus(601)).isEqualTo("PICKUP_PENDING");
        assertThat(count("return_tracking_events")).isEqualTo(1); // solo el registro inicial
        assertThat(count("notifications")).isZero();
        assertThat(jdbc.queryForObject("SELECT picked_up_at FROM return_shipments", Object.class)).isNull();

        reset(target(notifications));
        sendReturnEvent(601, "evt-1", "PICKED_UP", secondsAgo(5)).andExpect(jsonPath("$.result").value("APPLIED"));
        assertThat(returnStatus(601)).isEqualTo("PICKED_UP");
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void rnf015_failureWhileWritingTheAuditRollsBackTheReturnUpdate() throws Exception {
        int audits = count("audit_events");
        doThrow(new IllegalStateException("fallo inyectado")).when(target(audit))
                .record(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> sendReturnEvent(601, "evt-1", "PICKUP_FAILED", secondsAgo(5)))
                .hasRootCauseMessage("fallo inyectado");

        assertThat(returnStatus(601)).isEqualTo("PICKUP_PENDING");
        assertThat(jdbc.queryForMap("SELECT failed_pickups, pickup_stopped FROM return_shipments"))
                .containsEntry("failed_pickups", 0).containsEntry("pickup_stopped", false);
        assertThat(count("return_tracking_events")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(audits);
    }
}
