package com.transformersas.marketplace.logistics;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ShipmentReceipt;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
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
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Adaptador HTTP de logística contra WireMock (RNF-037/042/045): éxito, idempotencia, rechazo, error, timeout,
 * cuerpo inválido y Circuit Breaker. No requiere Spring ni base de datos.
 */
class HttpLogisticsGatewayTests {

    private static WireMockServer wiremock;

    private CircuitBreaker breaker;
    private HttpLogisticsGateway gateway;

    private static final ShipmentRequest REQUEST = new ShipmentRequest(7L, "EXPRESS",
            new ShipmentRequest.Recipient("Ana Comprador", "Calle 1 # 2-3", "Bogotá", "Cundinamarca", "110111",
                    "+57 300 123-4567"),
            List.of(new ShipmentRequest.Item("Lámpara", 2), new ShipmentRequest.Item("Mesa", 1)));

    private static final String CREATED = """
            {"shipmentId":"SHP-1","trackingCode":"TRK-1","status":"CREATED"}""";

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

    private void stubResponse(int status, String body) {
        wiremock.stubFor(post(urlEqualTo("/shipments")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void callAndIgnoreFailure() {
        try {
            gateway.createShipment(REQUEST, "order-7");
        } catch (RuntimeException expected) {
            // Se cuentan como fallos en el circuito.
        }
    }

    @Test
    void createsTheShipmentAndSendsTheContractHeadersAndBody() {
        MDC.put("correlationId", "corr-777");
        stubResponse(201, CREATED);

        ShipmentReceipt receipt = gateway.createShipment(REQUEST, "order-7");

        assertThat(receipt).isEqualTo(new ShipmentReceipt("SHP-1", "TRK-1"));
        wiremock.verify(1, postRequestedFor(urlEqualTo("/shipments"))
                .withHeader("Idempotency-Key", equalTo("order-7"))
                .withHeader("X-Correlation-Id", equalTo("corr-777"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.orderReference", equalTo("order-7")))
                .withRequestBody(matchingJsonPath("$.shippingMethod", equalTo("EXPRESS")))
                .withRequestBody(matchingJsonPath("$.recipient.name", equalTo("Ana Comprador")))
                .withRequestBody(matchingJsonPath("$.recipient.postalCode", equalTo("110111")))
                .withRequestBody(matchingJsonPath("$.items[0].name", equalTo("Lámpara")))
                .withRequestBody(matchingJsonPath("$.items[0].quantity", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.items[1].name", equalTo("Mesa"))));
    }

    @Test
    void repeatedKeyAnswered200ReturnsTheSameShipment() {
        stubResponse(201, CREATED);
        ShipmentReceipt first = gateway.createShipment(REQUEST, "order-7");
        stubResponse(200, CREATED);

        ShipmentReceipt second = gateway.createShipment(REQUEST, "order-7");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void clientErrorIsADefinitiveRejection() {
        stubResponse(400, "{\"error\":\"dirección inválida\"}");

        assertThatThrownBy(() -> gateway.createShipment(REQUEST, "order-7"))
                .isInstanceOf(LogisticsRejectedException.class).hasMessageContaining("400");
    }

    @Test
    void serverErrorIsATemporaryFailure() {
        stubResponse(500, "{}");

        assertThatThrownBy(() -> gateway.createShipment(REQUEST, "order-7"))
                .isInstanceOf(LogisticsUnavailableException.class).hasMessageContaining("500");
    }

    @Test
    void serviceUnavailableIsATemporaryFailure() {
        stubResponse(503, "");

        assertThatThrownBy(() -> gateway.createShipment(REQUEST, "order-7"))
                .isInstanceOf(LogisticsUnavailableException.class);
    }

    @Test
    void connectionResetIsATemporaryFailure() {
        wiremock.stubFor(post(urlEqualTo("/shipments")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> gateway.createShipment(REQUEST, "order-7"))
                .isInstanceOf(LogisticsUnavailableException.class);
    }

    @Test
    void invalidBodiesAreTemporaryFailures() {
        for (String body : new String[]{"no es json", "{}", "{\"shipmentId\":\"S\"}",
                "{\"shipmentId\":\"S\",\"trackingCode\":\"T\",\"status\":\"OTRO\"}",
                "{\"shipmentId\":\" \",\"trackingCode\":\"T\",\"status\":\"CREATED\"}"}) {
            stubResponse(201, body);
            assertThatThrownBy(() -> gateway.createShipment(REQUEST, "order-7")).as(body)
                    .isInstanceOf(LogisticsUnavailableException.class).hasMessageContaining("inválida");
        }
    }

    @Test
    void unexpectedSuccessCodeIsInvalid() {
        stubResponse(202, CREATED);

        assertThatThrownBy(() -> gateway.createShipment(REQUEST, "order-7"))
                .isInstanceOf(LogisticsUnavailableException.class);
    }

    @Test
    void slowResponseIsCutOffAtTheConfiguredTimeout() {
        wiremock.stubFor(post(urlEqualTo("/shipments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(CREATED).withFixedDelay(3_000)));
        long start = System.nanoTime();

        assertThatThrownBy(() -> gateway.createShipment(REQUEST, "order-7"))
                .isInstanceOf(LogisticsUnavailableException.class).hasMessageContaining("timeout");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(1_800), Duration.ofMillis(2_800));
    }

    /** RNF-042: con el timeout máximo (10 s, también el valor por defecto), una respuesta de 11 s se corta a los 10 s. */
    @Test
    void responseSlowerThanTenSecondsIsCutOffAtTenSeconds() {
        gateway = newGateway(Duration.ofSeconds(10));
        wiremock.stubFor(post(urlEqualTo("/shipments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(CREATED).withFixedDelay(11_000)));
        long start = System.nanoTime();

        assertThatThrownBy(() -> gateway.createShipment(REQUEST, "order-7"))
                .isInstanceOf(LogisticsUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(9_800), Duration.ofMillis(10_900));
    }

    @Test
    void timeoutAboveTenSecondsAndInsecureUrlsAreRejectedAtConfigurationTime() {
        assertThatThrownBy(() -> new LogisticsHttpProperties("https://x", Duration.ofSeconds(11), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10 s");
        assertThatThrownBy(() -> new LogisticsHttpProperties("https://x", Duration.ZERO, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpLogisticsGateway.create(
                new LogisticsHttpProperties("http://logistics.example", Duration.ofSeconds(5), true),
                RestClient.builder(), CircuitBreakerRegistry.ofDefaults()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> HttpLogisticsGateway.create(
                new LogisticsHttpProperties(" ", Duration.ofSeconds(5), false),
                RestClient.builder(), CircuitBreakerRegistry.ofDefaults()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndThenAnswersFastWithoutCallingTheProvider() {
        stubResponse(500, "{}");
        for (int i = 0; i < 5; i++) {
            callAndIgnoreFailure();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway.createShipment(REQUEST, "order-7"))
                .isInstanceOf(LogisticsUnavailableException.class).hasCauseInstanceOf(CallNotPermittedException.class);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMillis).isLessThan(200);
        wiremock.verify(5, postRequestedFor(urlEqualTo("/shipments"))); // la sexta llamada no llegó al proveedor
    }

    @Test
    void circuitRecoversThroughHalfOpenOnceTheProviderIsBack() {
        stubResponse(500, "{}");
        for (int i = 0; i < 5; i++) {
            callAndIgnoreFailure();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        stubResponse(201, CREATED);

        // Tras la espera (1 s) el circuito pasa a semiabierto y deja pasar la llamada de prueba.
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).ignoreExceptions().untilAsserted(() ->
                assertThat(gateway.createShipment(REQUEST, "order-7")).isEqualTo(new ShipmentReceipt("SHP-1", "TRK-1")));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        gateway.createShipment(REQUEST, "order-7");

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failedProbeInHalfOpenReopensTheCircuit() {
        stubResponse(500, "{}");
        for (int i = 0; i < 5; i++) {
            callAndIgnoreFailure();
        }

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).until(() -> {
            try {
                gateway.createShipment(REQUEST, "order-7");
                return false;
            } catch (LogisticsUnavailableException failure) {
                // El circuito sigue abierto (cause = CallNotPermitted) hasta que se permite la llamada de prueba.
                return !(failure.getCause() instanceof CallNotPermittedException);
            }
        });
        // Primera llamada de prueba fallida: en semiabierto se decide tras las 2 llamadas permitidas.
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        callAndIgnoreFailure();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void definitiveRejectionsDoNotOpenTheCircuit() {
        stubResponse(422, "{}");
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> gateway.createShipment(REQUEST, "order-7"))
                    .isInstanceOf(LogisticsRejectedException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        wiremock.verify(10, postRequestedFor(urlEqualTo("/shipments")));
    }
}
