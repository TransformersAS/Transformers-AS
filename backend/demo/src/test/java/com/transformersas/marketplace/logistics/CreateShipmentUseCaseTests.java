package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.application.dto.CreateShipmentCommand;
import com.transformersas.marketplace.logistics.application.dto.ShipmentResult;
import com.transformersas.marketplace.logistics.application.usecase.CreateShipmentUseCase;
import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.logistics.domain.model.ShipmentStatus;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentRepository;
import com.transformersas.marketplace.logistics.infrastructure.gateway.SimulatedLogisticsGateway;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RF-114/RF-115, A5/A6, RNF-043: la creación de envío es única e idempotente. */
class CreateShipmentUseCaseTests extends AbstractIntegrationTest {

    @Autowired CreateShipmentUseCase useCase;
    @Autowired LogisticsGateway gateway;
    @Autowired ShipmentRepository shipments;

    private SimulatedLogisticsGateway simulated;
    private long orderId;

    @BeforeEach
    void seed() {
        simulated = (SimulatedLogisticsGateway) gateway;
        simulated.reset();
        long product = seedProduct(1, "Lámpara", 10, "100.00");
        orderId = seedOrder(1, "READY_FOR_DISPATCH", product, 1, "100.00");
    }

    @AfterEach
    void clearMdc() {
        MDC.remove("correlationId");
    }

    private CreateShipmentCommand command() {
        return new CreateShipmentCommand(new ShipmentRequest(orderId, "STANDARD",
                new ShipmentRequest.Recipient("Ana", "Calle 1", "Bogotá", "Cundinamarca", "110111", "300"),
                List.of(new ShipmentRequest.Item("Lámpara", 1))), ActorType.SELLER, 15L);
    }

    @Test
    void defaultProviderIsTheSimulatedOne() {
        assertThat(gateway).isInstanceOf(SimulatedLogisticsGateway.class);
    }

    @Test
    void createsAndStoresTheReferenceAndAuditsItWithTheCorrelationId() {
        MDC.put("correlationId", "ship-corr-1");

        ShipmentResult result = useCase.execute(command());

        assertThat(result.created()).isTrue();
        assertThat(result.shipment().status()).isEqualTo(ShipmentStatus.CREATED);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM shipments");
        assertThat(row).containsEntry("order_id", orderId).containsEntry("idempotency_key", "order-" + orderId)
                .containsEntry("provider_shipment_id", "SIM-order-" + orderId)
                .containsEntry("tracking_code", "TRK-" + orderId).containsEntry("status", "CREATED");
        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM audit_events");
        assertThat(audit).containsEntry("action", "SHIPMENT_REQUEST").containsEntry("outcome", "SUCCESS")
                .containsEntry("actor_type", "SELLER").containsEntry("actor_id", 15L)
                .containsEntry("entity_id", String.valueOf(orderId)).containsEntry("correlation_id", "ship-corr-1");
    }

    @Test
    void secondRequestReusesTheExistingShipmentWithoutCallingTheProviderAgain() {
        ShipmentResult first = useCase.execute(command());

        ShipmentResult second = useCase.execute(command());

        assertThat(second.created()).isFalse();
        assertThat(second.shipment()).isEqualTo(first.shipment());
        assertThat(simulated.requestCount()).isEqualTo(1);
        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void temporaryFailureStoresNothingAuditsTheFailureAndAllowsASafeRetry() {
        simulated.setMode(SimulatedLogisticsGateway.Mode.UNAVAILABLE);

        assertThatThrownBy(() -> useCase.execute(command())).isInstanceOf(LogisticsUnavailableException.class);

        assertThat(count("shipments")).isZero();
        assertThat(jdbc.queryForMap("SELECT outcome, action FROM audit_events"))
                .containsEntry("outcome", "FAILURE").containsEntry("action", "SHIPMENT_REQUEST");

        simulated.setMode(SimulatedLogisticsGateway.Mode.OK);
        assertThat(useCase.execute(command()).created()).isTrue();
        assertThat(count("shipments")).isEqualTo(1);
        assertThat(simulated.distinctShipments()).isEqualTo(1);
    }

    @Test
    void definitiveRejectionIsPropagatedAndAudited() {
        simulated.setMode(SimulatedLogisticsGateway.Mode.REJECT);

        assertThatThrownBy(() -> useCase.execute(command())).isInstanceOf(LogisticsRejectedException.class);

        assertThat(count("shipments")).isZero();
        assertThat(jdbc.queryForObject("SELECT outcome FROM audit_events", String.class)).isEqualTo("FAILURE");
    }

    @Test
    void twoSimultaneousRequestsProduceExactlyOneShipmentAndOneCreation() throws Exception {
        var barrier = new CyclicBarrier(2);
        List<CompletableFuture<ShipmentResult>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return useCase.execute(command());
            }));
        }
        List<ShipmentResult> results = calls.stream().map(CompletableFuture::join).toList();

        assertThat(count("shipments")).isEqualTo(1);
        assertThat(results.stream().filter(ShipmentResult::created)).hasSize(1);
        assertThat(results.get(0).shipment().providerShipmentId()).isEqualTo(results.get(1).shipment().providerShipmentId());
        assertThat(simulated.distinctShipments()).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void repositoryResolvesTheRaceOnTheUniqueKeyByReturningTheExistingRow() {
        var candidate = new Shipment(null, orderId, "PROV-A", "TRK-A", "order-" + orderId, ShipmentStatus.CREATED,
                LocalDateTime.now());

        ShipmentRepository.Insertion first = shipments.insertIfAbsent(candidate);
        ShipmentRepository.Insertion second = shipments.insertIfAbsent(new Shipment(null, orderId, "PROV-B", "TRK-B",
                "order-" + orderId, ShipmentStatus.CREATED, LocalDateTime.now()));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.shipment().providerShipmentId()).isEqualTo("PROV-A");
        assertThat(count("shipments")).isEqualTo(1);
    }

    @Test
    void oneShipmentPerOrderIsEnforcedByTheDatabase() {
        useCase.execute(command());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO shipments(order_id, provider_shipment_id, tracking_code, idempotency_key, status, created_at)
                VALUES (?, 'X', 'Y', 'otra-clave', 'CREATED', NOW(6))""", orderId))
                .hasMessageContaining("uq_shipments_order");
    }
}
