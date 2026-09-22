package com.transformersas.marketplace.payments;

import com.transformersas.marketplace.payments.application.dto.RefundCommand;
import com.transformersas.marketplace.payments.application.usecase.RequestRefundUseCase;
import com.transformersas.marketplace.payments.domain.model.Refund;
import com.transformersas.marketplace.payments.domain.model.RefundStatus;
import com.transformersas.marketplace.payments.domain.repository.RefundRepository;
import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D10, RNF-009, RNF-043: reembolsos idempotentes por clave, con auditoría y sin efectos duplicados. */
class RequestRefundUseCaseTests extends AbstractIntegrationTest {

    @Autowired RequestRefundUseCase useCase;
    @Autowired SimulatedRefundGateway gateway;
    @Autowired RefundRepository refunds;
    @Autowired TransactionTemplate tx;

    private long order;

    @BeforeEach
    void setUp() {
        gateway.reset();
        order = seedOrder(1, "CANCELLED", seedProduct(1, "Lámpara", 5, "100.00"), 1, "100.00");
    }

    @AfterEach
    void clearMdc() {
        MDC.remove("correlationId");
    }

    private RefundCommand command(String key) {
        return command(key, "100.00");
    }

    private RefundCommand command(String key, String amount) {
        return new RefundCommand(order, new BigDecimal(amount), key, ActorType.SELLER, 15L);
    }

    private List<String> auditActions() {
        return jdbc.queryForList("SELECT CONCAT(action, ':', outcome) FROM audit_events ORDER BY id", String.class);
    }

    @Test
    void executeCompletesTheRefundAndAuditsRequestAndResultWithTheCorrelationId() {
        MDC.put("correlationId", "refund-corr-1");

        Refund refund = useCase.execute(command("order-cancel-" + order));

        assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.providerReference()).isEqualTo("SIM-REFUND-order-cancel-" + order);
        assertThat(refund.attempts()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM refunds");
        assertThat(row).containsEntry("order_id", order).containsEntry("status", "COMPLETED")
                .containsEntry("idempotency_key", "order-cancel-" + order).containsEntry("correlation_id", "refund-corr-1");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("100.00");
        assertThat(auditActions()).containsExactly("REFUND_REQUESTED:PENDING", "REFUND_RESULT:SUCCESS");
        assertThat(jdbc.queryForList("SELECT DISTINCT correlation_id FROM audit_events", String.class))
                .containsExactly("refund-corr-1");
        assertThat(jdbc.queryForList("SELECT DISTINCT actor_id FROM audit_events", Long.class)).containsExactly(15L);
    }

    @Test
    void rnf043_theSameKeyTwiceRefundsOnceAndDoesNotCallTheGatewayAgain() {
        Refund first = useCase.execute(command("order-cancel-" + order));

        Refund second = useCase.execute(command("order-cancel-" + order));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(gateway.requestCount()).isEqualTo(1);
        assertThat(gateway.distinctRefunds()).isEqualTo(1);
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(auditActions()).hasSize(2);
    }

    @Test
    void differentKeysAreDifferentRefunds() {
        // Dos causas distintas del mismo pedido: entre las dos no pueden pasar del total (100).
        useCase.execute(command("order-cancel-" + order, "50.00"));
        useCase.execute(command("return-77", "50.00"));

        assertThat(count("refunds")).isEqualTo(2);
        assertThat(gateway.distinctRefunds()).isEqualTo(2);
    }

    @Test
    void registerPendingRequiresAnOpenTransaction() {
        assertThatThrownBy(() -> useCase.registerPending(command("order-cancel-" + order)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count("refunds")).isZero();
    }

    @Test
    void registerPendingStoresAPendingRefundOnceAndItRollsBackWithTheCallersTransaction() {
        tx.executeWithoutResult(status -> {
            useCase.registerPending(command("order-cancel-" + order, "50.00"));
            useCase.registerPending(command("order-cancel-" + order, "50.00")); // idempotente
        });

        assertThat(jdbc.queryForMap("SELECT status, attempts FROM refunds")).containsEntry("status", "PENDING")
                .containsEntry("attempts", 0);
        assertThat(auditActions()).containsExactly("REFUND_REQUESTED:PENDING");
        assertThat(gateway.requestCount()).isZero(); // registrar no llama a la pasarela

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            useCase.registerPending(command("return-1", "50.00"));
            throw new IllegalStateException("fallo inyectado");
        })).hasMessage("fallo inyectado");
        assertThat(count("refunds")).isEqualTo(1);
    }

    @Test
    void registeredPendingRefundIsCompletedByExecuteWithoutDuplicatingIt() {
        tx.executeWithoutResult(status -> useCase.registerPending(command("order-cancel-" + order)));

        Refund refund = useCase.execute(command("order-cancel-" + order));

        assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(auditActions()).containsExactly("REFUND_REQUESTED:PENDING", "REFUND_RESULT:SUCCESS");
    }

    @Test
    void aGatewayFailureLeavesAFailedRefundThatCanBeRetriedWithTheSameKey() {
        gateway.setMode(SimulatedRefundGateway.Mode.UNAVAILABLE);

        Refund failed = useCase.execute(command("order-cancel-" + order));

        assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.lastError()).contains("no disponible");
        assertThat(auditActions()).containsExactly("REFUND_REQUESTED:PENDING", "REFUND_RESULT:FAILURE");

        gateway.setMode(SimulatedRefundGateway.Mode.OK);
        Refund retried = useCase.execute(command("order-cancel-" + order));

        assertThat(retried.id()).isEqualTo(failed.id());
        assertThat(retried.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(retried.attempts()).isEqualTo(2);
        assertThat(retried.lastError()).isNull();
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(gateway.distinctRefunds()).isEqualTo(1);
    }

    @Test
    void aDefinitiveRejectionIsAlsoRecordedAsFailed() {
        gateway.setMode(SimulatedRefundGateway.Mode.REJECT);

        Refund refund = useCase.execute(command("order-cancel-" + order));

        assertThat(refund.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.lastError()).contains("rechazado");
    }

    @Test
    void anAcceptedButUnconfirmedRefundStaysPendingAndCompletesLater() {
        gateway.setMode(SimulatedRefundGateway.Mode.PENDING);

        Refund pending = useCase.execute(command("order-cancel-" + order));

        assertThat(pending.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(pending.attempts()).isEqualTo(1);
        assertThat(auditActions()).containsExactly("REFUND_REQUESTED:PENDING"); // sin resultado final todavía

        gateway.setMode(SimulatedRefundGateway.Mode.OK);
        assertThat(useCase.execute(command("order-cancel-" + order)).status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(count("refunds")).isEqualTo(1);
    }

    @Test
    void rnf043_twoSimultaneousExecutionsProduceOneRefundAndOneSuccessAudit() {
        var barrier = new CyclicBarrier(2);
        List<CompletableFuture<Refund>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return useCase.execute(command("order-cancel-" + order));
            }));
        }
        List<Refund> results = calls.stream().map(CompletableFuture::join).toList();

        assertThat(results).allSatisfy(refund -> assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED));
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(gateway.distinctRefunds()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'REFUND_RESULT'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'REFUND_REQUESTED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void invalidCommandsAndAmountsAreRejected() {
        assertThatThrownBy(() -> new RefundCommand(order, BigDecimal.ZERO, "k", ActorType.SELLER, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundCommand(order, new BigDecimal("-1"), "k", ActorType.SELLER, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundCommand(order, BigDecimal.TEN, " ", ActorType.SELLER, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundCommand(null, BigDecimal.TEN, "k", ActorType.SELLER, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO refunds(order_id, amount, idempotency_key, status, correlation_id, created_at, updated_at)
                VALUES (?, 0, 'k', 'PENDING', 'c', NOW(6), NOW(6))""", order))
                .hasMessageContaining("chk_refunds_amount");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO refunds(order_id, amount, idempotency_key, status, correlation_id, created_at, updated_at)
                VALUES (?, 10, 'k', 'MAYBE', 'c', NOW(6), NOW(6))""", order))
                .hasMessageContaining("chk_refunds_status");
    }
}
