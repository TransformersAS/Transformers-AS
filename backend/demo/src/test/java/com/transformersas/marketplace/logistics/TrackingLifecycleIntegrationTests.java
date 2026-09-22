package com.transformersas.marketplace.logistics;
import com.transformersas.marketplace.support.AbstractTrackingTest;
import com.transformersas.marketplace.logistics.infrastructure.scheduling.TrackingPoller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties={"logistics.tracking.polling-enabled=true", "logistics.tracking.polling-interval=100ms",
        "logistics.tracking.refresh-min-interval=0ms", "logistics.simulated.script=HAPPY_PATH", "logistics.simulated.step=20ms"})
class TrackingLifecycleIntegrationTests extends AbstractTrackingTest {
    @Autowired TrackingPoller poller;
    @Test void backgroundLifecycleAdvancesRealPersistedShipmentsAndReturns() {
        poller.stop();
        try {
            assertThat(poller.isRunning()).isFalse();
            logistics.setScript(com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway.Script.HAPPY_PATH);
            long buyer=createAccount("tracking-lifecycle@example.test","COMPRADOR");
            long order=shippedOrder(buyer,"READY_FOR_DISPATCH");
            returnShipment(701L,buyer,1L,"PICKUP_PENDING",0);
            poller.start(); poller.start();
            assertThat(poller.isRunning()).isTrue();
            await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(orderStatus(order)).isEqualTo("DELIVERED"));
            await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(returnStatus(701L)).isEqualTo("DELIVERED_TO_SELLER"));
            assertThat(count("shipment_tracking_events")).isEqualTo(3);
            assertThat(count("return_tracking_events")).isEqualTo(3);
        } finally { poller.stop(); }
        assertThat(poller.isRunning()).isFalse();
        poller.stop();
    }
    @Test void failedSweepDoesNotPreventRecoveryAfterDatabaseIsAvailableAgain() {
        poller.stop();
        // Real SQL failure, not a replacement for the use case: temporarily rename only the test table.
        jdbc.execute("RENAME TABLE shipments TO shipments_unavailable");
        try { assertThatCode(poller::pollOnce).doesNotThrowAnyException(); }
        finally { jdbc.execute("RENAME TABLE shipments_unavailable TO shipments"); }
        assertThatCode(poller::pollOnce).doesNotThrowAnyException();
    }
}
