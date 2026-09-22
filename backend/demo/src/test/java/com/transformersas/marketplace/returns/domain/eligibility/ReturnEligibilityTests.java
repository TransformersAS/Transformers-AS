package com.transformersas.marketplace.returns.domain.eligibility;

import com.transformersas.marketplace.returns.domain.model.ReturnLine;
import com.transformersas.marketplace.returns.domain.port.OrderForReturn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Elegibilidad de una devolución (A1): reglas objetivas fijas y reglas del marketplace enchufables. */
class ReturnEligibilityTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 12, 0);
    private static final ReturnLine LINE = new ReturnLine(10L, 5L, "Lámpara", 1, new BigDecimal("10.00"));

    private static OrderForReturn order(boolean delivered, LocalDateTime deliveredAt) {
        return new OrderForReturn(1L, 7L, 1L, delivered, deliveredAt, List.of(LINE));
    }

    private static ReturnCandidate candidate(OrderForReturn order, Long itemId, int windowDays) {
        return new ReturnCandidate(order, itemId, windowDays, NOW);
    }

    private final ReturnEligibility eligibility = new ReturnEligibility(List.of());

    private String failureCode(ReturnCandidate candidate) {
        return eligibility.firstFailure(candidate).map(Ineligibility::code).orElse(null);
    }

    @Test
    void aLineOfADeliveredOrderInsideTheWindowIsEligible() {
        ReturnCandidate candidate = candidate(order(true, NOW.minusDays(5)), 10L, 30);

        assertThat(eligibility.isEligible(candidate)).isTrue();
        assertThat(eligibility.firstFailure(candidate)).isEmpty();
    }

    @Test
    void aProductThatIsNotInTheOrderIsNotEligible() {
        assertThat(failureCode(candidate(order(true, NOW.minusDays(1)), 99L, 30))).isEqualTo("RETURN_LINE_NOT_IN_ORDER");
    }

    @Test
    void anOrderThatIsNotDeliveredIsNotEligible() {
        assertThat(failureCode(candidate(order(false, null), 10L, 30))).isEqualTo("RETURN_ORDER_NOT_DELIVERED");
    }

    @Test
    void theWindowCountsFromTheDeliveryAndTheLastMomentStillCounts() {
        assertThat(failureCode(candidate(order(true, NOW.minusDays(30)), 10L, 30))).isNull();
        assertThat(failureCode(candidate(order(true, NOW.minusDays(30).minusSeconds(1)), 10L, 30)))
                .isEqualTo("RETURN_WINDOW_EXPIRED");
        assertThat(failureCode(candidate(order(true, NOW.minusDays(45)), 10L, 60))).isNull();
        assertThat(eligibility.firstFailure(candidate(order(true, NOW.minusDays(40)), 10L, 30)).orElseThrow()
                .message()).contains("30 días");
    }

    @Test
    void aDeliveredOrderWithoutADeliveryDateIsNotEligibleWithItsOwnReason() {
        assertThat(failureCode(candidate(order(true, null), 10L, 30))).isEqualTo("RETURN_DELIVERY_DATE_UNKNOWN");
    }

    @Test
    void theObjectiveRulesAreCheckedInOrderLineDeliveryThenWindow() {
        assertThat(failureCode(candidate(order(false, null), 99L, 30))).isEqualTo("RETURN_LINE_NOT_IN_ORDER");
        assertThat(failureCode(candidate(order(false, NOW.minusDays(90)), 10L, 30)))
                .isEqualTo("RETURN_ORDER_NOT_DELIVERED");
    }

    @Test
    void marketplaceRulesArePluggedInAndCheckedAfterTheObjectiveOnes() {
        ReturnEligibilityRule noElectronics = candidate -> Optional.of(new Ineligibility("RETURN_CATEGORY_EXCLUDED",
                "Esta categoría no admite devoluciones"));
        ReturnEligibility withRule = new ReturnEligibility(List.of(noElectronics));

        assertThat(withRule.firstFailure(candidate(order(true, NOW.minusDays(1)), 10L, 30)).orElseThrow().code())
                .isEqualTo("RETURN_CATEGORY_EXCLUDED");
        assertThat(withRule.firstFailure(candidate(order(false, null), 10L, 30)).orElseThrow().code())
                .isEqualTo("RETURN_ORDER_NOT_DELIVERED");
        assertThat(new ReturnEligibility(List.of()).isEligible(candidate(order(true, NOW.minusDays(1)), 10L, 30)))
                .isTrue();
    }

    @Test
    void theOrderFindsItsLinesById() {
        OrderForReturn order = order(true, NOW);

        assertThat(order.line(10L)).contains(LINE);
        assertThat(order.line(11L)).isEmpty();
    }
}
