package com.transformersas.marketplace.logistics;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.transformersas.marketplace.logistics.domain.model.*;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

/** Production Spring HTTP provider, real circuit breaker and WireMock transport. No mocked collaborators. */
class LogisticsHttpIntegrationTests extends AbstractIntegrationTest {
    static final WireMockServer server = new WireMockServer(options().dynamicPort());
    static { server.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("logistics.provider",()->"http");
        p.add("logistics.http.base-url",server::baseUrl);
        p.add("logistics.http.require-https",()->"false");
        p.add("logistics.http.timeout",()->"200ms");
    }
    @Autowired LogisticsGateway gateway;
    @Autowired CircuitBreakerRegistry registry;
    static final ShipmentRequest.Recipient RECIPIENT = new ShipmentRequest.Recipient("Ana","Calle 1","Bogotá","Bogotá","110111","1234567");
    static final ShipmentRequest SHIPMENT = new ShipmentRequest(7L,"STANDARD",RECIPIENT,List.of(new ShipmentRequest.Item("Café",2)));
    static final ReturnRequestData RETURN = new ReturnRequestData(42L,7L,1L,"PICKUP",RECIPIENT,SHIPMENT.items());
    @BeforeEach void resetProvider() { server.resetAll(); registry.circuitBreaker("logistics").reset(); }
    @AfterAll static void stop() { server.stop(); }
    void stub(String path, int status, String body) {
        server.stubFor(any(urlPathEqualTo(path)).willReturn(aResponse().withStatus(status).withHeader("Content-Type","application/json").withBody(body)));
    }
    @Test void shipmentReturnAndMethodsHonorHttpContract() {
        stub("/shipments",201,"{\"shipmentId\":\"S-7\",\"trackingCode\":\"T-7\",\"status\":\"CREATED\"}");
        assertThat(gateway.createShipment(SHIPMENT,SHIPMENT.idempotencyKey())).isEqualTo(new ShipmentReceipt("S-7","T-7"));
        stub("/returns",201,"{\"returnId\":\"R-42\",\"trackingCode\":\"T-42\",\"status\":\"CREATED\"}");
        assertThat(gateway.createReturn(RETURN,RETURN.idempotencyKey())).isEqualTo(new ReturnReceipt("R-42","T-42"));
        stub("/returns/methods",200,"{\"methods\":[{\"code\":\"PICKUP\",\"label\":\"Recogida\"},{\"code\":\"DROP\"},{\"code\":\" \"}]}");
        assertThat(gateway.fetchReturnMethods(7L,1L)).containsExactly(new ReturnMethod("PICKUP","Recogida"),new ReturnMethod("DROP","DROP"));
        server.verify(postRequestedFor(urlEqualTo("/shipments")).withHeader("Idempotency-Key",equalTo("order-7"))
                .withHeader("X-Correlation-Id",matching(".+"))
                .withRequestBody(matchingJsonPath("$.recipient.name",equalTo("Ana")))
                .withRequestBody(matchingJsonPath("$.items[0].quantity",equalTo("2"))));
        server.verify(postRequestedFor(urlEqualTo("/returns")).withHeader("Idempotency-Key",equalTo("return-42"))
                .withRequestBody(matchingJsonPath("$.orderReference",equalTo("order-7")))
                .withRequestBody(matchingJsonPath("$.pickup.street",equalTo("Calle 1"))));
        server.verify(getRequestedFor(urlPathEqualTo("/returns/methods")).withQueryParam("orderId",equalTo("7")).withQueryParam("storeId",equalTo("1")));
    }
    @Test void malformedEventsAreSkippedAndValidEvidenceSurvives() {
        String events="""
            {"events":[
              {"eventId":"e1","trackingCode":"T","type":"PICKED_UP","occurredAt":"2026-09-20T10:00:00Z"},
              {"eventId":"e2","trackingCode":"T","type":"IN_TRANSIT","occurredAt":"2026-09-20T11:00:00Z","evidence":{"type":"PHOTO","reference":"proof"}},
              {"eventId":"e3","type":"UNKNOWN"},
              {"eventId":"e4"},
              {"eventId":"e5","trackingCode":"T","type":"IN_TRANSIT","occurredAt":"invalid"},
              {"eventId":"bad id","trackingCode":"T","type":"IN_TRANSIT","occurredAt":"2026-09-20T11:00:00Z"},
              {"eventId":"e6","type":"IN_TRANSIT","occurredAt":"2026-09-20T11:00:00Z"}
            ]}
            """;
        stub("/shipments/S/events",200,events); stub("/returns/R/events",200,events);
        var shipment=gateway.fetchShipmentUpdates("S");
        var returns=gateway.fetchReturnUpdates("R");
        assertThat(shipment).extracting(TrackingUpdate::eventId).containsExactly("e1","e2");
        assertThat(returns).extracting(ReturnTrackingUpdate::eventId).containsExactly("e1","e2");
        assertThat(shipment.get(1).evidence().reference()).isEqualTo("proof");
        assertThat(returns.get(1).evidence().reference()).isEqualTo("proof");
    }
    @ParameterizedTest @ValueSource(strings={"{}","", "not-json"})
    void invalidProviderBodiesAreTemporaryFailures(String body) {
        for(String path:List.of("/shipments","/returns","/returns/methods","/shipments/S/events")) stub(path,200,body);
        assertThatThrownBy(()->gateway.createShipment(SHIPMENT,"order-7")).isInstanceOf(LogisticsUnavailableException.class);
        assertThatThrownBy(()->gateway.createReturn(RETURN,"return-42")).isInstanceOf(LogisticsUnavailableException.class);
        assertThatThrownBy(()->gateway.fetchReturnMethods(7L,1L)).isInstanceOf(LogisticsUnavailableException.class);
        assertThatThrownBy(()->gateway.fetchShipmentUpdates("S")).isInstanceOf(LogisticsUnavailableException.class);
    }
    @ParameterizedTest @ValueSource(ints={400,500})
    void errorsAreClassifiedAndNotRetried(int status) {
        stub("/returns",status,"{}");
        assertThatThrownBy(()->gateway.createReturn(RETURN,"return-42"))
                .isInstanceOf(status==400?LogisticsRejectedException.class:LogisticsUnavailableException.class);
        server.verify(1,postRequestedFor(urlEqualTo("/returns")));
    }
    @Test void timeoutAndConnectionFaultAreTemporary() {
        server.stubFor(post(urlEqualTo("/shipments")).willReturn(aResponse().withFixedDelay(700).withBody("{}")));
        assertThatThrownBy(()->gateway.createShipment(SHIPMENT,"order-7")).isInstanceOf(LogisticsUnavailableException.class);
        server.stubFor(post(urlEqualTo("/returns")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(()->gateway.createReturn(RETURN,"return-42")).isInstanceOf(LogisticsUnavailableException.class);
    }
    @Test void openCircuitPreventsEveryOperationFromContactingProvider() {
        registry.circuitBreaker("logistics").transitionToOpenState();
        assertThatThrownBy(()->gateway.createShipment(SHIPMENT,"order-7")).isInstanceOf(LogisticsUnavailableException.class);
        assertThatThrownBy(()->gateway.createReturn(RETURN,"return-42")).isInstanceOf(LogisticsUnavailableException.class);
        assertThatThrownBy(()->gateway.fetchReturnMethods(7L,1L)).isInstanceOf(LogisticsUnavailableException.class);
        assertThatThrownBy(()->gateway.fetchShipmentUpdates("S")).isInstanceOf(LogisticsUnavailableException.class);
        server.verify(0,anyRequestedFor(anyUrl()));
    }
}
