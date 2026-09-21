package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ReturnMethod;
import com.transformersas.marketplace.logistics.domain.model.ReturnReceipt;
import com.transformersas.marketplace.logistics.domain.model.ReturnRequestData;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway.Mode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retornos de CU-19 en el proveedor simulado: métodos, creación idempotente por "return-{id}" y sus tres modos. */
class SimulatedLogisticsGatewayReturnsTests {
    private static final ShipmentRequest.Recipient PICKUP = new ShipmentRequest.Recipient("Ana", "Calle 1", "Bogotá",
            "Cundinamarca", "110111", "+57 300 123-4567");

    private static ReturnRequestData request(long returnId, String method) {
        return new ReturnRequestData(returnId, 9L, 1L, method, PICKUP, List.of(new ShipmentRequest.Item("Lámpara", 2)));
    }

    private final SimulatedLogisticsGateway gateway = new SimulatedLogisticsGateway(Mode.OK);

    @Test
    void itOffersPickupAndDropOff() {
        assertThat(gateway.fetchReturnMethods(9L, 1L)).extracting(ReturnMethod::code)
                .containsExactly("PICKUP", "DROP_OFF");
    }

    @Test
    void creatingTheReturnIsIdempotentByKeyAndUsesTheTrackingConventionOfCu25() {
        ReturnReceipt first = gateway.createReturn(request(42, "PICKUP"), "return-42");
        ReturnReceipt again = gateway.createReturn(request(42, "DROP_OFF"), "return-42");

        assertThat(first).isEqualTo(new ReturnReceipt("SIM-return-42", "TRK-R42"));
        assertThat(again).isEqualTo(first);
        assertThat(gateway.distinctReturns()).isEqualTo(1);
        gateway.createReturn(request(43, "DROP_OFF"), "return-43");
        assertThat(gateway.distinctReturns()).isEqualTo(2);
        assertThat(gateway.requestCount()).isEqualTo(3);
    }

    @Test
    void theIdempotencyKeyOfARequestIsTheReturnId() {
        assertThat(request(42, "PICKUP").idempotencyKey()).isEqualTo("return-42");
    }

    @Test
    void aMethodThatIsNoLongerAvailableIsADefinitiveRejectionAndOtherMethodsStillWork() {
        gateway.setUnavailableReturnMethods(List.of("PICKUP"));

        assertThat(gateway.fetchReturnMethods(9L, 1L)).extracting(ReturnMethod::code).containsExactly("DROP_OFF");
        assertThatThrownBy(() -> gateway.createReturn(request(42, "PICKUP"), "return-42"))
                .isInstanceOf(LogisticsRejectedException.class).hasMessageContaining("PICKUP");
        assertThat(gateway.createReturn(request(42, "DROP_OFF"), "return-42").providerReturnId())
                .isEqualTo("SIM-return-42");
        assertThatThrownBy(() -> gateway.createReturn(request(44, "AIR_MAIL"), "return-44"))
                .isInstanceOf(LogisticsRejectedException.class);
    }

    @Test
    void theThreeModesApplyToBothOperations() {
        gateway.setMode(Mode.UNAVAILABLE);
        assertThatThrownBy(() -> gateway.fetchReturnMethods(9L, 1L)).isInstanceOf(LogisticsUnavailableException.class);
        assertThatThrownBy(() -> gateway.createReturn(request(42, "PICKUP"), "return-42"))
                .isInstanceOf(LogisticsUnavailableException.class);
        gateway.setMode(Mode.REJECT);
        assertThatThrownBy(() -> gateway.fetchReturnMethods(9L, 1L)).isInstanceOf(LogisticsRejectedException.class);
        assertThatThrownBy(() -> gateway.createReturn(request(42, "PICKUP"), "return-42"))
                .isInstanceOf(LogisticsRejectedException.class);
        assertThat(gateway.distinctReturns()).isZero();

        gateway.setMode(Mode.OK);
        assertThat(gateway.createReturn(request(42, "PICKUP"), "return-42")).isNotNull();
    }

    @Test
    void resetForgetsReturnsAndUnavailableMethods() {
        gateway.setUnavailableReturnMethods(List.of("PICKUP"));
        gateway.createReturn(request(42, "DROP_OFF"), "return-42");

        gateway.reset();

        assertThat(gateway.distinctReturns()).isZero();
        assertThat(gateway.fetchReturnMethods(9L, 1L)).hasSize(2);
    }

    @Test
    void aReturnRequestNeedsItsEssentialDataAndDoesNotExposeThePickupAddress() {
        assertThatThrownBy(() -> new ReturnRequestData(0L, 9L, 1L, "PICKUP", PICKUP, List.of(new ShipmentRequest.Item("x", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnRequestData(1L, 9L, 1L, " ", PICKUP, List.of(new ShipmentRequest.Item("x", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnRequestData(1L, 9L, 1L, "PICKUP", null, List.of(new ShipmentRequest.Item("x", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnRequestData(1L, 9L, 1L, "PICKUP", PICKUP, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(request(1, "PICKUP").toString()).doesNotContain("Calle 1").doesNotContain("Ana");
    }
}
