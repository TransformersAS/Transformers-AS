package com.transformersas.marketplace.orders;

import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.transformersas.marketplace.orders.domain.model.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transiciones de transporte de un pedido (CU-24, A1 a A6): solo las provoca el servicio logístico, nunca retroceden
 * y Entregado y Retornado al vendedor son finales. Se comprueban las 11 x 11 combinaciones.
 */
class OrderLogisticsTransitionTests {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            READY_FOR_DISPATCH, EnumSet.of(PICKED_UP, IN_TRANSIT),
            PICKED_UP, EnumSet.of(IN_TRANSIT, DELIVERY_EXCEPTION, DELIVERED),
            IN_TRANSIT, EnumSet.of(DELIVERED, DELIVERY_EXCEPTION, DELIVERY_ATTEMPT_FAILED, RETURNED_TO_SELLER),
            DELIVERY_EXCEPTION, EnumSet.of(IN_TRANSIT, DELIVERED, DELIVERY_EXCEPTION, DELIVERY_ATTEMPT_FAILED,
                    RETURNED_TO_SELLER),
            DELIVERY_ATTEMPT_FAILED, EnumSet.of(IN_TRANSIT, DELIVERED, DELIVERY_EXCEPTION, DELIVERY_ATTEMPT_FAILED,
                    RETURNED_TO_SELLER));

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void logisticsTargetsAreExactlyTheListedOnes(OrderStatus from) {
        assertThat(from.allowedLogisticsTargets()).isEqualTo(ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "RETURNED_TO_SELLER", "CANCELLED",
            "CANCELLATION_REQUESTED", "CONFIRMED", "IN_PREPARATION"})
    void statesOutsideTheTransportPhaseAcceptNoLogisticsUpdate(OrderStatus from) {
        assertThat(from.allowedLogisticsTargets()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void noLogisticsTransitionGoesBackwardsToAStateBeforeDispatch(OrderStatus from) {
        assertThat(from.allowedLogisticsTargets()).doesNotContain(CONFIRMED, IN_PREPARATION, READY_FOR_DISPATCH,
                CANCELLED, CANCELLATION_REQUESTED);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void sellerAndBuyerTransitionsAreUnchangedByTheLogisticsOnes(OrderStatus from) {
        Set<OrderStatus> expected = switch (from) {
            case CONFIRMED -> EnumSet.of(IN_PREPARATION, CANCELLED);
            case IN_PREPARATION -> EnumSet.of(READY_FOR_DISPATCH, CANCELLED);
            default -> EnumSet.noneOf(OrderStatus.class);
        };
        assertThat(from.allowedTargets()).isEqualTo(expected);
    }
}
