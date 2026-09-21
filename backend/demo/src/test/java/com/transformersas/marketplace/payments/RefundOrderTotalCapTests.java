package com.transformersas.marketplace.payments;

import com.transformersas.marketplace.claims.ClaimResponse;
import com.transformersas.marketplace.claims.ClaimService;
import com.transformersas.marketplace.claims.ClaimStatus;
import com.transformersas.marketplace.payments.application.dto.RefundCommand;
import com.transformersas.marketplace.payments.application.usecase.RequestRefundUseCase;
import com.transformersas.marketplace.payments.domain.model.Refund;
import com.transformersas.marketplace.payments.domain.model.RefundStatus;
import com.transformersas.marketplace.payments.infrastructure.gateway.SimulatedRefundGateway;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Un pedido no se reembolsa por más de lo que costó (orders.total), sea cual sea la causa: cancelación, reclamación
 * (CU-13) o devolución (CU-19, clave return-{id}). Lo que falló no cuenta; lo pendiente sí; repetir una clave no cuenta
 * de nuevo; y dos reembolsos simultáneos del mismo pedido no pueden pasarse entre los dos.
 */
class RefundOrderTotalCapTests extends AbstractIntegrationTest {
    @Autowired RequestRefundUseCase refunds;
    @Autowired SimulatedRefundGateway gateway;
    @Autowired ClaimService claims;

    private long buyerId;
    private long product;
    private long order;

    @BeforeEach
    @AfterEach
    void cleanClaims() {
        List.of("claim_messages", "claim_evidences", "claims").forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    @BeforeEach
    void seed() {
        gateway.reset();
        buyerId = createAccount("comprador@example.com", "COMPRADOR");
        product = seedProduct(1, "Lámpara", 5, "100.00");
        order = seedOrder(1, "DELIVERED", product, 1, "100.00");
        jdbc.update("UPDATE orders SET account_id = ? WHERE id = ?", buyerId, order);
    }

    private RefundCommand refund(String key, String amount) {
        return new RefundCommand(order, new BigDecimal(amount), key, ActorType.SELLER, 15L);
    }

    private static void assertExceedsTotal(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo("REFUND_EXCEEDS_ORDER_TOTAL");
        });
    }

    @Test
    void refundsCanAddUpToTheTotalButNotBeyondIt() {
        refunds.execute(refund("return-1", "60.00"));

        assertExceedsTotal(() -> refunds.execute(refund("claim-1", "40.01")));
        assertThat(count("refunds")).isEqualTo(1);
        refunds.execute(refund("claim-1", "40.00"));
        assertExceedsTotal(() -> refunds.execute(refund("return-2", "0.01")));
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM refunds", BigDecimal.class))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void repeatingAKeyThatIsAlreadyRegisteredIsNotCountedAgainEvenAtTheLimit() {
        Refund first = refunds.execute(refund("return-1", "100.00"));

        Refund again = refunds.execute(refund("return-1", "100.00"));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(count("refunds")).isEqualTo(1);
    }

    @Test
    void aFailedRefundDoesNotCountButRetryingItBringsItBack() {
        gateway.setMode(SimulatedRefundGateway.Mode.UNAVAILABLE);
        Refund failed = refunds.execute(refund("return-1", "100.00"));
        assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
        gateway.setMode(SimulatedRefundGateway.Mode.OK);

        Refund other = refunds.execute(refund("claim-1", "100.00"));

        assertThat(other.status()).isEqualTo(RefundStatus.COMPLETED);
        assertExceedsTotal(() -> refunds.execute(refund("return-1", "100.00")));
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE idempotency_key = 'return-1'", String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void aFailedRefundCanBeRetriedWhileThereIsRoomForIt() {
        gateway.setMode(SimulatedRefundGateway.Mode.UNAVAILABLE);
        refunds.execute(refund("return-1", "100.00"));
        gateway.setMode(SimulatedRefundGateway.Mode.OK);

        Refund retried = refunds.execute(refund("return-1", "100.00"));

        assertThat(retried.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(count("refunds")).isEqualTo(1);
    }

    @Test
    void aRefundStillPendingAtTheGatewayCountsTowardsTheTotal() {
        gateway.setMode(SimulatedRefundGateway.Mode.PENDING);
        assertThat(refunds.execute(refund("return-1", "70.00")).status()).isEqualTo(RefundStatus.PENDING);

        assertExceedsTotal(() -> refunds.execute(refund("claim-1", "30.01")));
        refunds.execute(refund("claim-1", "30.00"));
    }

    @Test
    void aFullOrderCancellationRefundLeavesNoRoomForAnyOtherRefund() {
        refunds.execute(refund("order-cancel-" + order, "100.00"));

        assertExceedsTotal(() -> refunds.execute(refund("return-1", "1.00")));
        assertExceedsTotal(() -> refunds.execute(refund("claim-1", "1.00")));
    }

    @Test
    void twoSimultaneousRefundsOfTheSameOrderCannotBothPass() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<CompletableFuture<String>> attempts = List.of("return-1", "claim-1").stream()
                .map(key -> CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await();
                        refunds.execute(refund(key, "60.00"));
                        return "OK";
                    } catch (BusinessException rejected) {
                        return rejected.code();
                    } catch (Exception other) {
                        return other.toString();
                    }
                })).toList();

        List<String> outcomes = attempts.stream().map(CompletableFuture::join).sorted().toList();

        assertThat(outcomes).containsExactly("OK", "REFUND_EXCEEDS_ORDER_TOTAL");
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM refunds", BigDecimal.class))
                .isEqualByComparingTo("60.00");
    }

    @Test
    void theClaimRouteIsBlockedAndTheClaimStaysOpenWhenItsRefundWouldExceedTheTotal() {
        refunds.execute(refund("return-1", "60.00"));
        ClaimResponse claim = claims.open(buyerId, order, product, "Llegó roto", List.of());
        claims.propose(1L, 99L, claim.id(), "Te devolvemos 60", new BigDecimal("60.00"));

        assertExceedsTotal(() -> claims.accept(buyerId, claim.id()));

        assertThat(claims.getForBuyer(buyerId, claim.id()).status()).isEqualTo(ClaimStatus.SOLUTION_PROPOSED);
        assertThat(count("refunds")).isEqualTo(1);
        claims.propose(1L, 99L, claim.id(), "Mejor 40", new BigDecimal("40.00"));
        assertThat(claims.accept(buyerId, claim.id()).status()).isEqualTo(ClaimStatus.RESOLVED);
        assertThat(count("refunds")).isEqualTo(2);
    }

    @Test
    void theReturnRouteUsesTheSameLimitWithItsOwnKey() {
        refunds.execute(refund("claim-9", "80.00"));

        assertExceedsTotal(() -> refunds.execute(refund("return-5", "20.01")));
        assertThat(refunds.execute(refund("return-5", "20.00")).idempotencyKey()).isEqualTo("return-5");
    }
}
