package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Proveedor logístico simulado (RNF-037): seguimiento de envíos y retornos con éxito, fallo y guion automático. */
class SimulatedLogisticsGatewayTrackingTests {

    private final SimulatedLogisticsGateway gateway = new SimulatedLogisticsGateway(SimulatedLogisticsGateway.Mode.OK);

    private static TrackingUpdate update(String id, ShipmentEventType type) {
        return new TrackingUpdate(id, "SIM-order-1", "TRK-1", type, Instant.now(), null, null, null);
    }

    @Test
    void publishedUpdatesAreReturnedForThatShipmentOnly() {
        gateway.publish(update("e-1", ShipmentEventType.PICKED_UP));
        gateway.publish(update("e-2", ShipmentEventType.IN_TRANSIT));

        assertThat(gateway.fetchShipmentUpdates("SIM-order-1")).extracting(TrackingUpdate::eventId)
                .containsExactly("e-1", "e-2");
        assertThat(gateway.fetchShipmentUpdates("SIM-order-2")).isEmpty();
        assertThat(gateway.requestCount()).isEqualTo(2);
    }

    @Test
    void publishedReturnUpdatesAreReturnedForThatReturnOnly() {
        gateway.publishReturn(new ReturnTrackingUpdate("r-1", "SIM-return-1", "TRK-R1", ReturnEventType.PICKED_UP,
                Instant.now(), null, null, null));

        assertThat(gateway.fetchReturnUpdates("SIM-return-1")).hasSize(1);
        assertThat(gateway.fetchReturnUpdates("SIM-return-2")).isEmpty();
    }

    @Test
    void theModeMakesQueriesFailWithTheProviderExceptions() {
        gateway.setMode(SimulatedLogisticsGateway.Mode.UNAVAILABLE);
        assertThatThrownBy(() -> gateway.fetchShipmentUpdates("SIM-order-1")).isInstanceOf(LogisticsUnavailableException.class);
        assertThatThrownBy(() -> gateway.fetchReturnUpdates("SIM-return-1")).isInstanceOf(LogisticsUnavailableException.class);

        gateway.setMode(SimulatedLogisticsGateway.Mode.REJECT);
        assertThatThrownBy(() -> gateway.fetchShipmentUpdates("SIM-order-1")).isInstanceOf(LogisticsRejectedException.class);
        assertThatThrownBy(() -> gateway.fetchReturnUpdates("SIM-return-1")).isInstanceOf(LogisticsRejectedException.class);
    }

    @Test
    void resetClearsUpdatesModeAndScript() {
        gateway.publish(update("e-1", ShipmentEventType.PICKED_UP));
        gateway.setMode(SimulatedLogisticsGateway.Mode.UNAVAILABLE);
        gateway.setScript(SimulatedLogisticsGateway.Script.HAPPY_PATH);

        gateway.reset();

        assertThat(gateway.fetchShipmentUpdates("SIM-order-1")).isEmpty();
        assertThat(gateway.requestCount()).isEqualTo(1);
    }

    @Test
    void theHappyPathScriptAdvancesTheShipmentByItselfWithADeliveryEvidence() {
        ReflectionTestUtils.setField(gateway, "step", Duration.ofMillis(150));
        gateway.setScript(SimulatedLogisticsGateway.Script.HAPPY_PATH);

        assertThat(gateway.fetchShipmentUpdates("SIM-order-7")).isEmpty(); // aún no pasó ningún paso

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertThat(gateway.fetchShipmentUpdates("SIM-order-7")).extracting(TrackingUpdate::type)
                        .containsExactly(ShipmentEventType.PICKED_UP, ShipmentEventType.IN_TRANSIT,
                                ShipmentEventType.DELIVERED));
        var delivered = gateway.fetchShipmentUpdates("SIM-order-7").get(2);
        assertThat(delivered.trackingCode()).isEqualTo("TRK-7");
        assertThat(delivered.shipmentId()).isEqualTo("SIM-order-7");
        assertThat(delivered.evidence().reference()).isEqualTo("SIM-POD-TRK-7");
        // Los identificadores son deterministas: repetir la consulta devuelve los mismos eventos.
        assertThat(gateway.fetchShipmentUpdates("SIM-order-7").get(0).eventId()).isEqualTo("sim-SIM-order-7-PICKED_UP");
    }

    @Test
    void theHappyPathScriptAdvancesTheReturnByItself() {
        ReflectionTestUtils.setField(gateway, "step", Duration.ofMillis(150));
        gateway.setScript(SimulatedLogisticsGateway.Script.HAPPY_PATH);

        assertThat(gateway.fetchReturnUpdates("SIM-return-3")).isEmpty();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertThat(gateway.fetchReturnUpdates("SIM-return-3")).extracting(ReturnTrackingUpdate::type)
                        .containsExactly(ReturnEventType.PICKED_UP, ReturnEventType.IN_TRANSIT,
                                ReturnEventType.DELIVERED_TO_SELLER));
        var delivered = gateway.fetchReturnUpdates("SIM-return-3").get(2);
        assertThat(delivered.trackingCode()).isEqualTo("TRK-R3");
        assertThat(delivered.evidence().reference()).isEqualTo("SIM-POD-TRK-R3");
    }
}
