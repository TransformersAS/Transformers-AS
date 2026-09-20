package com.transformersas.marketplace.logistics;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
import com.transformersas.marketplace.logistics.infrastructure.gateway.HttpLogisticsGateway;
import com.transformersas.marketplace.logistics.infrastructure.gateway.LogisticsHttpProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consulta de seguimiento de envíos y retornos contra WireMock (CU-24 paso 1, CU-25 paso 3, RNF-037, RNF-042,
 * RNF-045): éxito, actualizaciones inválidas omitidas, rechazo, error, timeout y Circuit Breaker.
 */
class HttpLogisticsGatewayTrackingTests {

    private static WireMockServer wiremock;

    private CircuitBreaker breaker;
    private HttpLogisticsGateway gateway;

    private static final String SHIPMENT_EVENTS = """
            {"events":[
              {"eventId":"e-1","trackingCode":"TRK-1","type":"PICKED_UP","occurredAt":"2026-09-20T10:00:00Z",
               "description":"Recogido","location":"Bogotá","extra":"ignorado"},
              {"eventId":"e-2","trackingCode":"TRK-1","type":"DELIVERED","occurredAt":"2026-09-20T12:00:00Z",
               "evidence":{"type":"SIGNATURE","reference":"POD-1"}}
            ]}""";

    @BeforeAll
    static void startWireMock() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wiremock.stop();
    }

    @BeforeEach
    void setUp() {
        wiremock.resetAll();
        gateway = newGateway(Duration.ofSeconds(2));
    }

    @AfterEach
    void clearMdc() {
        MDC.remove("correlationId");
    }

    private HttpLogisticsGateway newGateway(Duration timeout) {
        var config = CircuitBreakerConfig.custom()
                .slidingWindowSize(5).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(1)).permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(LogisticsUnavailableException.class)
                .ignoreExceptions(LogisticsRejectedException.class).build();
        var registry = CircuitBreakerRegistry.of(config);
        breaker = registry.circuitBreaker("logistics");
        var properties = new LogisticsHttpProperties("http://localhost:" + wiremock.port(), timeout, false);
        return HttpLogisticsGateway.create(properties, RestClient.builder(), registry);
    }

    private void stub(String path, int status, String body) {
        wiremock.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    // ---------- Envíos ----------

    @Test
    void fetchesTheShipmentTimelineAndSendsTheCorrelationId() {
        MDC.put("correlationId", "corr-24");
        stub("/shipments/SHP-1/events", 200, SHIPMENT_EVENTS);

        List<TrackingUpdate> updates = gateway.fetchShipmentUpdates("SHP-1");

        assertThat(updates).hasSize(2);
        assertThat(updates.get(0).eventId()).isEqualTo("e-1");
        assertThat(updates.get(0).shipmentId()).isEqualTo("SHP-1");
        assertThat(updates.get(0).type()).isEqualTo(ShipmentEventType.PICKED_UP);
        assertThat(updates.get(0).occurredAt()).isEqualTo(Instant.parse("2026-09-20T10:00:00Z"));
        assertThat(updates.get(0).location()).isEqualTo("Bogotá");
        assertThat(updates.get(1).evidence().reference()).isEqualTo("POD-1");
        wiremock.verify(1, getRequestedFor(urlEqualTo("/shipments/SHP-1/events"))
                .withHeader("X-Correlation-Id", equalTo("corr-24")));
    }

    @Test
    void anEmptyTimelineIsNotAnError() {
        stub("/shipments/SHP-1/events", 200, "{\"events\":[]}");

        assertThat(gateway.fetchShipmentUpdates("SHP-1")).isEmpty();
    }

    @Test
    void unknownOrMalformedUpdatesAreSkippedWithoutFailingTheWholeQuery() {
        stub("/shipments/SHP-1/events", 200, """
                {"events":[
                  {"eventId":"e-1","trackingCode":"TRK-1","type":"TELEPORTED","occurredAt":"2026-09-20T10:00:00Z"},
                  {"eventId":"e-2","trackingCode":"TRK-1","type":"PICKED_UP","occurredAt":"ayer"},
                  {"eventId":"e-3","trackingCode":"TRK-1","type":"PICKED_UP"},
                  {"eventId":"e 4","trackingCode":"TRK-1","type":"PICKED_UP","occurredAt":"2026-09-20T10:00:00Z"},
                  {"eventId":"e-5","type":"PICKED_UP","occurredAt":"2026-09-20T10:00:00Z"},
                  {"eventId":"e-6","trackingCode":"TRK-1","type":"IN_TRANSIT","occurredAt":"2026-09-20T11:00:00Z"}
                ]}""");

        List<TrackingUpdate> updates = gateway.fetchShipmentUpdates("SHP-1");

        assertThat(updates).extracting(TrackingUpdate::eventId).containsExactly("e-6");
    }

    @Test
    void aClientErrorIsADefinitiveRejection() {
        stub("/shipments/SHP-1/events", 404, "{}");

        assertThatThrownBy(() -> gateway.fetchShipmentUpdates("SHP-1")).isInstanceOf(LogisticsRejectedException.class)
                .hasMessageContaining("404");
    }

    @Test
    void serverErrorsConnectionFaultsAndInvalidBodiesAreTemporaryFailures() {
        stub("/shipments/SHP-1/events", 500, "{}");
        assertThatThrownBy(() -> gateway.fetchShipmentUpdates("SHP-1")).isInstanceOf(LogisticsUnavailableException.class)
                .hasMessageContaining("500");

        wiremock.stubFor(get(urlEqualTo("/shipments/SHP-1/events")).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> gateway.fetchShipmentUpdates("SHP-1")).isInstanceOf(LogisticsUnavailableException.class);

        for (String body : new String[]{"no es json", "{}", "{\"events\":null}"}) {
            stub("/shipments/SHP-1/events", 200, body);
            assertThatThrownBy(() -> gateway.fetchShipmentUpdates("SHP-1")).as(body)
                    .isInstanceOf(LogisticsUnavailableException.class).hasMessageContaining("inválida");
        }
        stub("/shipments/SHP-1/events", 204, "");
        assertThatThrownBy(() -> gateway.fetchShipmentUpdates("SHP-1")).isInstanceOf(LogisticsUnavailableException.class);
    }

    @Test
    void aSlowResponseIsCutOffAtTheConfiguredTimeout() {
        wiremock.stubFor(get(urlEqualTo("/shipments/SHP-1/events")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(SHIPMENT_EVENTS).withFixedDelay(3_000)));
        long start = System.nanoTime();

        assertThatThrownBy(() -> gateway.fetchShipmentUpdates("SHP-1"))
                .isInstanceOf(LogisticsUnavailableException.class).hasMessageContaining("timeout");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(1_800), Duration.ofMillis(2_800));
    }

    @Test
    void theCircuitOpensAfterRepeatedFailuresAndThenAnswersWithoutCallingTheProvider() {
        stub("/shipments/SHP-1/events", 500, "{}");
        for (int i = 0; i < 5; i++) {
            try {
                gateway.fetchShipmentUpdates("SHP-1");
            } catch (LogisticsUnavailableException expected) {
                // Cuenta como fallo del circuito.
            }
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> gateway.fetchShipmentUpdates("SHP-1")).isInstanceOf(LogisticsUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThatThrownBy(() -> gateway.fetchReturnUpdates("RET-1")).isInstanceOf(LogisticsUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);

        wiremock.verify(5, getRequestedFor(urlEqualTo("/shipments/SHP-1/events")));
        wiremock.verify(0, getRequestedFor(urlEqualTo("/returns/RET-1/events")));
    }

    // ---------- Retornos ----------

    @Test
    void fetchesTheReturnTimeline() {
        stub("/returns/RET-1/events", 200, """
                {"events":[
                  {"eventId":"r-1","trackingCode":"TRK-R1","type":"PICKED_UP","occurredAt":"2026-09-20T10:00:00Z"},
                  {"eventId":"r-2","trackingCode":"TRK-R1","type":"PICKUP_FAILED","occurredAt":"2026-09-20T11:00:00Z",
                   "description":"Ausente"},
                  {"eventId":"r-3","trackingCode":"TRK-R1","type":"DELIVERED_TO_SELLER","occurredAt":"2026-09-20T12:00:00Z",
                   "evidence":{"type":"CODE","reference":"POD-R1"}},
                  {"eventId":"r-4","trackingCode":"TRK-R1","type":"OTHER","occurredAt":"2026-09-20T12:00:00Z"},
                  {"eventId":"r-5","trackingCode":"TRK-R1","type":"PICKED_UP","occurredAt":"nunca"}
                ]}""");

        List<ReturnTrackingUpdate> updates = gateway.fetchReturnUpdates("RET-1");

        assertThat(updates).extracting(ReturnTrackingUpdate::type).containsExactly(ReturnEventType.PICKED_UP,
                ReturnEventType.PICKUP_FAILED, ReturnEventType.DELIVERED_TO_SELLER);
        assertThat(updates.get(0).returnId()).isEqualTo("RET-1");
        assertThat(updates.get(1).description()).isEqualTo("Ausente");
        assertThat(updates.get(2).evidence().reference()).isEqualTo("POD-R1");
    }

    @Test
    void returnQueriesClassifyFailuresLikeShipmentQueries() {
        stub("/returns/RET-1/events", 400, "{}");
        assertThatThrownBy(() -> gateway.fetchReturnUpdates("RET-1")).isInstanceOf(LogisticsRejectedException.class);

        stub("/returns/RET-1/events", 503, "{}");
        assertThatThrownBy(() -> gateway.fetchReturnUpdates("RET-1")).isInstanceOf(LogisticsUnavailableException.class);

        stub("/returns/RET-1/events", 200, "{\"events\":null}");
        assertThatThrownBy(() -> gateway.fetchReturnUpdates("RET-1")).isInstanceOf(LogisticsUnavailableException.class);
    }
}
