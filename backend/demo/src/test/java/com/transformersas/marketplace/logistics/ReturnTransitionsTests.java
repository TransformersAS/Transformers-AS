package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.ReturnStatus;
import com.transformersas.marketplace.logistics.domain.model.ReturnTransitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Máquina de estados del retorno de una devolución (CU-25, RF-110, A1 a A5): cada combinación de estado y tipo de
 * actualización tiene un resultado definido. Ninguna retrocede el estado y la tercera recogida fallida lo detiene.
 */
class ReturnTransitionsTests {

    private static ReturnShipment shipment(ReturnStatus status, boolean pickedUp, int failedPickups, boolean stopped) {
        LocalDateTime now = LocalDateTime.now();
        return new ReturnShipment(1L, 10L, 5L, 1L, "SIM-return-10", "TRK-R10", status, failedPickups, stopped,
                pickedUp ? now : null, null, now, now);
    }

    @ParameterizedTest(name = "{0} (recogido={1}) + {2} -> {3}")
    @CsvSource({
            // Recogida pendiente
            "PICKUP_PENDING, false, PICKUP_SCHEDULED, RECORD, PICKUP_PENDING",
            "PICKUP_PENDING, false, PICKED_UP, APPLY, PICKED_UP",
            "PICKUP_PENDING, false, IN_TRANSIT, APPLY, IN_RETURN",
            "PICKUP_PENDING, false, INCIDENT, APPLY, LOGISTICS_ISSUE",
            "PICKUP_PENDING, false, PICKUP_FAILED, APPLY, PICKUP_FAILED",
            "PICKUP_PENDING, false, DELIVERED_TO_SELLER, REJECT, PICKUP_PENDING",
            // Recogido
            "PICKED_UP, true, PICKUP_SCHEDULED, REJECT, PICKED_UP",
            "PICKED_UP, true, PICKED_UP, RECORD, PICKED_UP",
            "PICKED_UP, true, IN_TRANSIT, APPLY, IN_RETURN",
            "PICKED_UP, true, INCIDENT, APPLY, LOGISTICS_ISSUE",
            "PICKED_UP, true, PICKUP_FAILED, REJECT, PICKED_UP",
            "PICKED_UP, true, DELIVERED_TO_SELLER, APPLY, DELIVERED_TO_SELLER",
            // En retorno
            "IN_RETURN, true, PICKUP_SCHEDULED, REJECT, IN_RETURN",
            "IN_RETURN, true, PICKED_UP, REJECT, IN_RETURN",
            "IN_RETURN, true, IN_TRANSIT, RECORD, IN_RETURN",
            "IN_RETURN, true, INCIDENT, APPLY, LOGISTICS_ISSUE",
            "IN_RETURN, true, PICKUP_FAILED, REJECT, IN_RETURN",
            "IN_RETURN, true, DELIVERED_TO_SELLER, APPLY, DELIVERED_TO_SELLER",
            // Novedad logística antes de la recogida
            "LOGISTICS_ISSUE, false, PICKUP_SCHEDULED, REJECT, LOGISTICS_ISSUE",
            "LOGISTICS_ISSUE, false, PICKED_UP, APPLY, PICKED_UP",
            "LOGISTICS_ISSUE, false, IN_TRANSIT, APPLY, IN_RETURN",
            "LOGISTICS_ISSUE, false, INCIDENT, APPLY, LOGISTICS_ISSUE",
            "LOGISTICS_ISSUE, false, PICKUP_FAILED, APPLY, PICKUP_FAILED",
            "LOGISTICS_ISSUE, false, DELIVERED_TO_SELLER, REJECT, LOGISTICS_ISSUE",
            // Novedad logística después de la recogida
            "LOGISTICS_ISSUE, true, PICKUP_SCHEDULED, REJECT, LOGISTICS_ISSUE",
            "LOGISTICS_ISSUE, true, PICKED_UP, REJECT, LOGISTICS_ISSUE",
            "LOGISTICS_ISSUE, true, IN_TRANSIT, APPLY, IN_RETURN",
            "LOGISTICS_ISSUE, true, INCIDENT, APPLY, LOGISTICS_ISSUE",
            "LOGISTICS_ISSUE, true, PICKUP_FAILED, REJECT, LOGISTICS_ISSUE",
            "LOGISTICS_ISSUE, true, DELIVERED_TO_SELLER, APPLY, DELIVERED_TO_SELLER",
            // Recogida fallida (todavía se pueden pedir más recogidas)
            "PICKUP_FAILED, false, PICKUP_SCHEDULED, APPLY, PICKUP_PENDING",
            "PICKUP_FAILED, false, PICKED_UP, APPLY, PICKED_UP",
            "PICKUP_FAILED, false, IN_TRANSIT, REJECT, PICKUP_FAILED",
            "PICKUP_FAILED, false, INCIDENT, REJECT, PICKUP_FAILED",
            "PICKUP_FAILED, false, PICKUP_FAILED, APPLY, PICKUP_FAILED",
            "PICKUP_FAILED, false, DELIVERED_TO_SELLER, REJECT, PICKUP_FAILED",
    })
    void everyStateAndUpdateTypeHasADefinedResult(ReturnStatus status, boolean pickedUp, ReturnEventType type,
                                                  ReturnTransitions.Result result, ReturnStatus target) {
        var evaluation = ReturnTransitions.evaluate(shipment(status, pickedUp, status == ReturnStatus.PICKUP_FAILED ? 1 : 0,
                false), type);

        assertThat(evaluation.result()).isEqualTo(result);
        assertThat(evaluation.target()).isEqualTo(target);
        assertThat(evaluation.pickupStopped()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ReturnEventType.class)
    void aDeliveredReturnAcceptsNoFurtherChange(ReturnEventType type) {
        var evaluation = ReturnTransitions.evaluate(shipment(ReturnStatus.DELIVERED_TO_SELLER, true, 0, false), type);

        assertThat(evaluation.result()).isEqualTo(ReturnTransitions.Result.REJECT);
        assertThat(evaluation.target()).isEqualTo(ReturnStatus.DELIVERED_TO_SELLER);
    }

    @ParameterizedTest
    @EnumSource(ReturnEventType.class)
    void onceThePickupsAreStoppedNothingIsAcceptedUntilTheCaseIsHandled(ReturnEventType type) {
        var evaluation = ReturnTransitions.evaluate(shipment(ReturnStatus.PICKUP_FAILED, false, 3, true), type);

        assertThat(evaluation.result()).isEqualTo(ReturnTransitions.Result.REJECT);
        assertThat(evaluation.failedPickups()).isEqualTo(3);
        assertThat(evaluation.pickupStopped()).isTrue();
    }

    @Test
    void aFailedPickupCountsAndTheThirdOneStopsFurtherAttempts() {
        var first = ReturnTransitions.evaluate(shipment(ReturnStatus.PICKUP_PENDING, false, 0, false),
                ReturnEventType.PICKUP_FAILED);
        var second = ReturnTransitions.evaluate(shipment(ReturnStatus.PICKUP_PENDING, false, 1, false),
                ReturnEventType.PICKUP_FAILED);
        var third = ReturnTransitions.evaluate(shipment(ReturnStatus.PICKUP_PENDING, false, 2, false),
                ReturnEventType.PICKUP_FAILED);

        assertThat(first.failedPickups()).isEqualTo(1);
        assertThat(first.pickupStopped()).isFalse();
        assertThat(second.failedPickups()).isEqualTo(2);
        assertThat(second.pickupStopped()).isFalse();
        assertThat(third.failedPickups()).isEqualTo(3);
        assertThat(third.pickupStopped()).isTrue();
    }

    @Test
    void aNewPickupCanBeRequestedOnlyAfterAFailureWhileTheLimitIsNotReached() {
        assertThat(shipment(ReturnStatus.PICKUP_FAILED, false, 1, false).canRequestNewPickup()).isTrue();
        assertThat(shipment(ReturnStatus.PICKUP_FAILED, false, 2, false).canRequestNewPickup()).isTrue();
        assertThat(shipment(ReturnStatus.PICKUP_FAILED, false, 3, true).canRequestNewPickup()).isFalse();
        assertThat(shipment(ReturnStatus.PICKUP_PENDING, false, 0, false).canRequestNewPickup()).isFalse();
        assertThat(shipment(ReturnStatus.PICKED_UP, true, 0, false).canRequestNewPickup()).isFalse();
    }
}
