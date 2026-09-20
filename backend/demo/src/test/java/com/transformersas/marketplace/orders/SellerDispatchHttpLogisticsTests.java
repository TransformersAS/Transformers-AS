package com.transformersas.marketplace.orders;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;
import com.transformersas.marketplace.logistics.infrastructure.gateway.HttpLogisticsGateway;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.http.Fault;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Despacho contra un servicio logístico HTTP simulado con WireMock (RNF-037/038/042/045, A4, A5): éxito, error,
 * timeout, indisponibilidad y circuito abierto. En todos los casos el pedido queda Listo para despacho y el reintento
 * es seguro.
 */
class SellerDispatchHttpLogisticsTests extends AbstractIntegrationTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

    private static final String CREATED = """
            {"shipmentId":"SHP-77","trackingCode":"TRK-77","status":"CREATED"}""";

    static {
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void logisticsProperties(DynamicPropertyRegistry registry) {
        registry.add("logistics.provider", () -> "http");
        registry.add("logistics.http.base-url", () -> "http://localhost:" + WIREMOCK.port());
        registry.add("logistics.http.require-https", () -> "false");
        registry.add("logistics.http.timeout", () -> "1s"); // el corte real de 10 s se prueba en HttpLogisticsGatewayTests
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @Autowired LogisticsGateway gateway;
    @Autowired CircuitBreakerRegistry registry;

    private Session seller;
    private long product;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM notifications");
        WIREMOCK.resetAll();
        registry.circuitBreaker("logistics").reset();
        seller = sellerOfStore("seller@example.com", 1);
        product = seedProduct(1, "Lámpara", 7, "100.00");
    }

    private void stubShipments(int status, String body) {
        WIREMOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/shipments")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private ResultActions ready(long order) throws Exception {
        return performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/ready-for-dispatch")
                .header("X-Correlation-Id", "dispatch-corr-" + order));
    }

    private ResultActions retry(long order) throws Exception {
        return performAsSeller(seller, 1, post("/api/seller/orders/" + order + "/shipment"));
    }

    private String statusOf(long order) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order);
    }

    @Test
    void httpProviderIsSelected() {
        assertThat(gateway).isInstanceOf(HttpLogisticsGateway.class);
    }

    @Test
    void rnf038_a4_theProviderReceivesTheCorrelationIdTheIdempotencyKeyAndTheDeliverySnapshot() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 2, "100.00");
        jdbc.update("UPDATE addresses SET street = 'Calle cambiada 99', city = 'Medellín', phone = '000'");
        stubShipments(201, CREATED);

        ready(order).andExpect(status().isOk()).andExpect(jsonPath("$.shipment.status").value("CREATED"))
                .andExpect(jsonPath("$.shipment.shipmentId").value("SHP-77"))
                .andExpect(jsonPath("$.shipment.trackingCode").value("TRK-77"));

        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/shipments"))
                .withHeader("Idempotency-Key", equalTo("order-" + order))
                .withHeader("X-Correlation-Id", equalTo("dispatch-corr-" + order))
                .withRequestBody(matchingJsonPath("$.orderReference", equalTo("order-" + order)))
                .withRequestBody(matchingJsonPath("$.shippingMethod", equalTo("STANDARD")))
                // Datos de la compra, no de la dirección modificada después.
                .withRequestBody(matchingJsonPath("$.recipient.name", equalTo("Ana Comprador")))
                .withRequestBody(matchingJsonPath("$.recipient.street", equalTo("Calle 1 # 2-3")))
                .withRequestBody(matchingJsonPath("$.recipient.city", equalTo("Bogotá")))
                .withRequestBody(matchingJsonPath("$.recipient.phone", equalTo("+57 300 123-4567")))
                .withRequestBody(matchingJsonPath("$.items[0].name", equalTo("Lámpara")))
                .withRequestBody(matchingJsonPath("$.items[0].quantity", equalTo("2"))));
        // El mismo id de correlación queda en historial, auditoría y notificación (RNF-038).
        String correlation = "dispatch-corr-" + order;
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM order_status_history WHERE to_status = 'READY_FOR_DISPATCH'",
                String.class)).isEqualTo(correlation);
        assertThat(jdbc.queryForList("SELECT DISTINCT correlation_id FROM audit_events", String.class))
                .containsExactly(correlation);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM notifications", String.class)).isEqualTo(correlation);
        assertThat(jdbc.queryForObject("SELECT tracking_code FROM shipments", String.class)).isEqualTo("TRK-77");
    }

    @Test
    void rnf045_aServerErrorKeepsTheOrderReadyAndTheRetryReusesTheSameIdempotencyKey() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        stubShipments(500, "{}");

        ready(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_FOR_DISPATCH"))
                .andExpect(jsonPath("$.shipment.status").value("FAILED"));
        assertThat(statusOf(order)).isEqualTo("READY_FOR_DISPATCH");
        assertThat(count("shipments")).isZero();

        stubShipments(201, CREATED);
        retry(order).andExpect(status().isCreated()).andExpect(jsonPath("$.shipmentId").value("SHP-77"));
        retry(order).andExpect(status().isOk()); // ya existe: no vuelve a llamar al proveedor

        WIREMOCK.verify(2, postRequestedFor(urlEqualTo("/shipments")).withHeader("Idempotency-Key", equalTo("order-" + order)));
        assertThat(count("shipments")).isEqualTo(1);
    }

    @Test
    void rnf042_045_aSlowProviderIsCutOffAndTheOrderStaysReady() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        WIREMOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/shipments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(CREATED).withFixedDelay(3_000)));

        ready(order).andExpect(status().isOk()).andExpect(jsonPath("$.shipment.status").value("FAILED"));

        assertThat(statusOf(order)).isEqualTo("READY_FOR_DISPATCH");
        assertThat(count("shipments")).isZero();
        stubShipments(201, CREATED);
        retry(order).andExpect(status().isCreated());
    }

    @Test
    void rnf045_anUnreachableProviderIsReportedWithoutBreakingTheOrder() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        WIREMOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/shipments")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ready(order).andExpect(status().isOk()).andExpect(jsonPath("$.shipment.status").value("FAILED"));
        retry(order).andExpect(status().isBadGateway());

        assertThat(statusOf(order)).isEqualTo("READY_FOR_DISPATCH");
    }

    @Test
    void a5_aClientErrorFromTheProviderIsReportedAsRejected() throws Exception {
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");
        stubShipments(422, "{\"error\":\"dirección inválida\"}");

        ready(order).andExpect(status().isOk()).andExpect(jsonPath("$.shipment.status").value("FAILED"))
                .andExpect(jsonPath("$.shipment.message").value(containsString("rechazó")));

        assertThat(statusOf(order)).isEqualTo("READY_FOR_DISPATCH");
    }

    @Test
    void rnf045_theCircuitOpensAfterRepeatedFailuresAndDispatchAnswersFastWithoutCallingTheProvider() throws Exception {
        stubShipments(500, "{}");
        for (int i = 0; i < 5; i++) {
            ready(seedOrder(1, "IN_PREPARATION", product, 1, "100.00")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.shipment.status").value("FAILED"));
        }
        assertThat(registry.circuitBreaker("logistics").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        long order = seedOrder(1, "IN_PREPARATION", product, 1, "100.00");

        long start = System.nanoTime();
        ready(order).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_FOR_DISPATCH"))
                .andExpect(jsonPath("$.shipment.status").value("FAILED"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(1_500);
        WIREMOCK.verify(5, postRequestedFor(urlEqualTo("/shipments"))); // la sexta no llegó al proveedor
        assertThat(statusOf(order)).isEqualTo("READY_FOR_DISPATCH");
        // Recuperación: con el circuito cerrado de nuevo y el proveedor sano, el reintento crea el envío.
        registry.circuitBreaker("logistics").reset();
        stubShipments(201, CREATED);
        retry(order).andExpect(status().isCreated());
    }
}
