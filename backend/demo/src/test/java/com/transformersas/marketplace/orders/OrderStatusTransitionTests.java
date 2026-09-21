package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.transformersas.marketplace.orders.domain.model.OrderStatus.*;

/** Reglas de transición de D5: todas las combinaciones origen x destino (10 x 10). */
class OrderStatusTransitionTests {

    private static final Set<String> VALID = Set.of(
            "CONFIRMED>IN_PREPARATION", "IN_PREPARATION>READY_FOR_DISPATCH",
            "CONFIRMED>CANCELLED", "IN_PREPARATION>CANCELLED");

    static Stream<Arguments> allCombinations() {
        return EnumSet.allOf(OrderStatus.class).stream()
                .flatMap(from -> EnumSet.allOf(OrderStatus.class).stream().map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allCombinations")
    void everyCombinationIsAllowedOnlyIfListedInD5(OrderStatus from, OrderStatus to) {
        boolean expected = VALID.contains(from + ">" + to);

        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
        if (expected) {
            from.requireTransitionTo(to);
        } else {
            assertThatThrownBy(() -> from.requireTransitionTo(to))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.kind()).isEqualTo(BusinessException.Kind.CONFLICT);
                        assertThat(error.code()).isEqualTo("ORDER_INVALID_TRANSITION");
                    });
        }
    }

    @Test
    void thereAreTheTenCu23StatesPlusCancellationRequestedAndFourValidTransitions() {
        // Los 10 de D5 más CANCELLATION_REQUESTED (CU-11, solicitud del comprador), que aún no tiene transiciones.
        assertThat(OrderStatus.values()).hasSize(11);
        long valid = allCombinations().filter(args -> ((OrderStatus) args.get()[0])
                .canTransitionTo((OrderStatus) args.get()[1])).count();
        assertThat(valid).isEqualTo(4);
    }

    @Test
    void logisticsStatesAndTerminalStatesHaveNoOutgoingTransitionsInThisRelease() {
        for (OrderStatus status : EnumSet.complementOf(EnumSet.of(CONFIRMED, IN_PREPARATION))) {
            assertThat(status.allowedTargets()).as(status.name()).isEmpty();
        }
    }

    @Test
    void preparationIssuesOnlyAllowedBeforeTheOrderIsReady() {
        assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::allowsPreparationIssues))
                .containsExactlyInAnyOrder(CONFIRMED, IN_PREPARATION);
    }
}
