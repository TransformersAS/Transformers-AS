package com.transformersas.marketplace.logistics;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ReturnMethod;
import com.transformersas.marketplace.logistics.domain.model.ReturnReceipt;
import com.transformersas.marketplace.logistics.domain.model.ReturnRequestData;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.logistics.infrastructure.gateway.HttpLogisticsGateway;
import com.transformersas.marketplace.logistics.infrastructure.gateway.LogisticsHttpProperties;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retornos de CU-19 en el adaptador HTTP contra WireMock: consulta de métodos y creación con Idempotency-Key, con el mismo
 * circuito, timeout y clasificación de errores que crear un envío, y sin reintentos automáticos.
 */
class HttpLogisticsGatewayReturnsTests {
    private static WireMockServer wiremock;

    private static final ReturnRequestData REQUEST = new ReturnRequestData(42L, 9L, 1L, "PICKUP",
            new ShipmentRequest.Recipient("Ana Comprador", "Calle 1 # 2-3", "Bogotá", "Cundinamarca", "110111",
                    "+57 300 123-4567"), List.of(new ShipmentRequest.Item("Lámpara", 2)));
    private static final String CREATED = "{\"returnId\":\"RET-1\",\"trackingCode\":\"TRK-R1\",\"status\":\"CREATED\"}";

    private CircuitBreaker breaker;
    private HttpLogisticsGateway gateway;

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
        var config = CircuitBreakerConfig.custom().slidingWindowSize(5).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(1)).permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(LogisticsUnavailableException.class)
                .ignoreExceptions(LogisticsRejectedException.class).build();
        var registry = CircuitBreakerRegistry.of(config);
        breaker = registry.circuitBreaker("logistics");
        gateway = HttpLogisticsGateway.create(new LogisticsHttpProperties("http://localhost:" + wiremock.port(),
                Duration.ofSeconds(2), false), RestClient.builder(), registry);
    }

    @AfterEach
    void clearMdc() {
        MDC.remove("correlationId");
    }

    private void stubCreate(int status, String body) {
        wiremock.stubFor(post(urlEqualTo("/returns")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void itAsksForTheMethodsOfAnOrderAndAStoreAndSendsTheCorrelationId() {
        MDC.put("correlationId", "corr-19");
        wiremock.stubFor(get(urlPathEqualTo("/returns/methods")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"methods\":[{\"code\":\"PICKUP\",\"label\":\"Recogida\"},{\"code\":\"DROP_OFF\"},{\"label\":\"sin código\"}]}")));

        List<ReturnMethod> methods = gateway.fetchReturnMethods(9L, 1L);

        assertThat(methods).containsExactly(new ReturnMethod("PICKUP", "Recogida"), new ReturnMethod("DROP_OFF", "DROP_OFF"));
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/returns/methods"))
                .withQueryParam("orderId", equalTo("9")).withQueryParam("storeId", equalTo("1"))
                .withHeader("X-Correlation-Id", equalTo("corr-19")));
    }

    @Test
    void creatingTheReturnSendsTheIdempotencyKeyTheStoreIdAndNoStoreAddress() {
        MDC.put("correlationId", "corr-19");
        stubCreate(201, CREATED);

        ReturnReceipt receipt = gateway.createReturn(REQUEST, "return-42");

        assertThat(receipt).isEqualTo(new ReturnReceipt("RET-1", "TRK-R1"));
        wiremock.verify(1, postRequestedFor(urlEqualTo("/returns"))
                .withHeader("Idempotency-Key", equalTo("return-42"))
                .withHeader("X-Correlation-Id", equalTo("corr-19"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.returnReference", equalTo("return-42")))
                .withRequestBody(matchingJsonPath("$.orderReference", equalTo("order-9")))
                .withRequestBody(matchingJsonPath("$.storeId", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.method", equalTo("PICKUP")))
                .withRequestBody(matchingJsonPath("$.pickup.name", equalTo("Ana Comprador")))
                .withRequestBody(matchingJsonPath("$.pickup.postalCode", equalTo("110111")))
                .withRequestBody(matchingJsonPath("$.items[0].name", equalTo("Lámpara")))
                .withRequestBody(matchingJsonPath("$.items[0].quantity", equalTo("2")))
                .withRequestBody(notMatching(".*destination.*")).withRequestBody(notMatching(".*storeAddress.*")));
    }

    @Test
    void aRepeatedKeyAnswered200ReturnsTheSameReturn() {
        stubCreate(201, CREATED);
        ReturnReceipt first = gateway.createReturn(REQUEST, "return-42");
        stubCreate(200, CREATED);

        assertThat(gateway.createReturn(REQUEST, "return-42")).isEqualTo(first);
    }

    @Test
    void aClientErrorIsADefinitiveRejectionForBothOperations() {
        stubCreate(409, "{\"error\":\"método no disponible\"}");
        wiremock.stubFor(get(urlPathEqualTo("/returns/methods")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> gateway.createReturn(REQUEST, "return-42"))
                .isInstanceOf(LogisticsRejectedException.class).hasMessageContaining("409");
        assertThatThrownBy(() -> gateway.fetchReturnMethods(9L, 1L)).isInstanceOf(LogisticsRejectedException.class)
                .hasMessageContaining("404");
    }

    @Test
    void serverErrorsBadBodiesAndTimeoutsAreTemporaryAndNeverRetried() {
        stubCreate(500, "{}");
        assertThatThrownBy(() -> gateway.createReturn(REQUEST, "return-42"))
                .isInstanceOf(LogisticsUnavailableException.class).hasMessageContaining("500");
        wiremock.verify(1, postRequestedFor(urlEqualTo("/returns")));

        stubCreate(201, "{\"returnId\":\"\",\"trackingCode\":\"T\",\"status\":\"CREATED\"}");
        assertThatThrownBy(() -> gateway.createReturn(REQUEST, "return-42"))
                .isInstanceOf(LogisticsUnavailableException.class);
        stubCreate(201, "{\"returnId\":\"R\",\"trackingCode\":\"T\",\"status\":\"PENDING\"}");
        assertThatThrownBy(() -> gateway.createReturn(REQUEST, "return-42"))
                .isInstanceOf(LogisticsUnavailableException.class);
        wiremock.stubFor(get(urlPathEqualTo("/returns/methods")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        assertThatThrownBy(() -> gateway.fetchReturnMethods(9L, 1L)).isInstanceOf(LogisticsUnavailableException.class);
        wiremock.stubFor(post(urlEqualTo("/returns")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> gateway.createReturn(REQUEST, "return-42"))
                .isInstanceOf(LogisticsUnavailableException.class);
    }

    @Test
    void anOpenCircuitDoesNotCallTheProviderAndAnswersUnavailable() {
        stubCreate(500, "{}");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> gateway.createReturn(REQUEST, "return-42"))
                    .isInstanceOf(LogisticsUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        wiremock.resetRequests();

        assertThatThrownBy(() -> gateway.createReturn(REQUEST, "return-42"))
                .isInstanceOf(LogisticsUnavailableException.class).hasMessageContaining("no disponible");
        assertThatThrownBy(() -> gateway.fetchReturnMethods(9L, 1L)).isInstanceOf(LogisticsUnavailableException.class);
        wiremock.verify(0, postRequestedFor(urlEqualTo("/returns")));
        wiremock.verify(0, getRequestedFor(urlPathEqualTo("/returns/methods")));
    }
}
