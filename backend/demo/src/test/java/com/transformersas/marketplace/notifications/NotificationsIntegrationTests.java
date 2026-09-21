package com.transformersas.marketplace.notifications;

import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.application.usecase.DispatchExternalNotificationUseCase;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.domain.model.Notification;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.notifications.domain.repository.ExternalNotificationGateway;
import com.transformersas.marketplace.notifications.infrastructure.gateway.SimulatedExternalNotificationGateway;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** RF-123, A7, RNF-043, RNF-045: notificación interna transaccional y aviso externo posterior al commit. */
class NotificationsIntegrationTests extends AbstractIntegrationTest {

    @Autowired PublishNotificationUseCase publish;
    @Autowired DispatchExternalNotificationUseCase dispatch;
    @Autowired TransactionTemplate tx;
    @Autowired ExternalNotificationGateway gateway;

    private SimulatedExternalNotificationGateway external;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notifications");
        external = (SimulatedExternalNotificationGateway) gateway;
        external.reset();
    }

    @AfterEach
    void clearMdc() {
        MDC.remove("correlationId");
    }

    private PublishNotificationCommand command(String eventKey) {
        return new PublishNotificationCommand(RecipientType.BUYER, null, "ORDER_IN_PREPARATION",
                "Tu pedido está en preparación", "Estamos preparando tu pedido #42.", "ORDER", "42", eventKey);
    }

    private String externalStatus(String eventKey) {
        return jdbc.queryForObject("SELECT external_status FROM notifications WHERE event_key = ?", String.class, eventKey);
    }

    private void awaitStatus(String eventKey, String expected) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                .until(() -> expected.equals(externalStatus(eventKey)));
    }

    @Test
    void publishedNotificationIsStoredInternallyAndTheExternalNoticeIsSentOnceAfterCommit() {
        MDC.put("correlationId", "notif-corr-1");

        tx.executeWithoutResult(status -> publish.execute(command("order-42-IN_PREPARATION")));

        awaitStatus("order-42-IN_PREPARATION", "SENT");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM notifications");
        assertThat(row).containsEntry("recipient_type", "BUYER").containsEntry("type", "ORDER_IN_PREPARATION")
                .containsEntry("reference_type", "ORDER").containsEntry("reference_id", "42")
                .containsEntry("attempts", 1).containsEntry("correlation_id", "notif-corr-1");
        assertThat(row.get("recipient_id")).isNull();
        assertThat(row.get("last_error")).isNull();
        // El aviso externo lleva el mismo id de correlación aunque salga desde otro hilo (RNF-038).
        assertThat(external.accepted()).hasSize(1);
        assertThat(external.accepted().get(0).correlationId()).isEqualTo("notif-corr-1");
        assertThat(external.accepted().get(0).eventKey()).isEqualTo("order-42-IN_PREPARATION");
    }

    @Test
    void sameEventKeyPublishedTwiceProducesOneNotificationAndOneExternalNotice() {
        tx.executeWithoutResult(status -> {
            publish.execute(command("order-42-IN_PREPARATION"));
            publish.execute(command("order-42-IN_PREPARATION")); // misma transacción
        });
        tx.executeWithoutResult(status -> publish.execute(command("order-42-IN_PREPARATION"))); // otra transacción

        awaitStatus("order-42-IN_PREPARATION", "SENT");
        // Ventana de observación: no debe aparecer ningún segundo aviso.
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> external.requestCount() == 1);
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void twoConcurrentTransactionsWithTheSameEventKeyLeaveExactlyOneNotification() {
        var barrier = new CyclicBarrier(2);
        List<CompletableFuture<Notification>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> tx.execute(status -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return publish.execute(command("order-7-CANCELLED"));
            })));
        }
        List<Notification> results = calls.stream().map(CompletableFuture::join).toList();

        assertThat(results.get(0).id()).isEqualTo(results.get(1).id());
        awaitStatus("order-7-CANCELLED", "SENT");
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(external.accepted()).hasSize(1);
    }

    @Test
    void temporaryExternalFailureKeepsTheInternalNotificationAndMarksItFailed() {
        external.setMode(SimulatedExternalNotificationGateway.Mode.UNAVAILABLE);

        tx.executeWithoutResult(status -> publish.execute(command("order-42-IN_PREPARATION")));

        awaitStatus("order-42-IN_PREPARATION", "FAILED");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM notifications");
        assertThat(row).containsEntry("attempts", 1).containsEntry("title", "Tu pedido está en preparación");
        assertThat((String) row.get("last_error")).contains("no disponible");
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void definitiveExternalRejectionAlsoLeavesTheInternalNotificationIntact() {
        external.setMode(SimulatedExternalNotificationGateway.Mode.REJECT);

        tx.executeWithoutResult(status -> publish.execute(command("order-42-IN_PREPARATION")));

        awaitStatus("order-42-IN_PREPARATION", "FAILED");
        assertThat((String) jdbc.queryForObject("SELECT last_error FROM notifications", String.class))
                .contains("rechazado");
    }

    @Test
    void rolledBackTransactionStoresNothingAndSendsNothingExternally() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            publish.execute(command("order-42-IN_PREPARATION"));
            throw new IllegalStateException("fallo inyectado");
        })).hasMessage("fallo inyectado");

        assertThat(count("notifications")).isZero();
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> external.requestCount() == 0);
    }

    @Test
    void publishingWithoutAnOpenTransactionIsRejected() {
        assertThatThrownBy(() -> publish.execute(command("order-42-IN_PREPARATION")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("notifications")).isZero();
    }

    @Test
    void dispatchingAnAlreadySentNotificationDoesNotSendItAgain() {
        tx.executeWithoutResult(status -> publish.execute(command("order-42-IN_PREPARATION")));
        awaitStatus("order-42-IN_PREPARATION", "SENT");
        Long id = jdbc.queryForObject("SELECT id FROM notifications", Long.class);

        dispatch.execute(id);
        dispatch.execute(id);

        assertThat(external.requestCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM notifications", Integer.class)).isEqualTo(1);
    }

    @Test
    void dispatchingAnUnknownNotificationIsANoOp() {
        dispatch.execute(999_999L);

        assertThat(external.requestCount()).isZero();
    }

    @Test
    void commandRejectsMissingOrOversizedFields() {
        assertThatThrownBy(() -> new PublishNotificationCommand(null, null, "T", "t", "m", "ORDER", "1", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublishNotificationCommand(RecipientType.STORE, 1L, "T", " ", "m", "ORDER", "1", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublishNotificationCommand(RecipientType.STORE, 1L, "T", "t", "m".repeat(1001),
                "ORDER", "1", "k")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseRejectsInvalidRecipientAndStatusValues() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO notifications(recipient_type, type, title, message, reference_type, reference_id, event_key,
                                          external_status, correlation_id, created_at, updated_at)
                VALUES ('ALIEN','T','t','m','ORDER','1','k1','PENDING','c',NOW(6),NOW(6))"""))
                .hasMessageContaining("chk_notifications_recipient_type");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO notifications(recipient_type, type, title, message, reference_type, reference_id, event_key,
                                          external_status, correlation_id, created_at, updated_at)
                VALUES ('BUYER','T','t','m','ORDER','1','k2','MAYBE','c',NOW(6),NOW(6))"""))
                .hasMessageContaining("chk_notifications_external_status");
    }
}
