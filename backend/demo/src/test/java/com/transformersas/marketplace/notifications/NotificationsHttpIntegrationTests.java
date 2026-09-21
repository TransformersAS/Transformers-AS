package com.transformersas.marketplace.notifications;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.notifications.domain.repository.ExternalNotificationGateway;
import com.transformersas.marketplace.notifications.infrastructure.gateway.HttpExternalNotificationGateway;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Aviso externo por HTTP contra WireMock (RNF-037/042/045, A7): éxito, error 5xx, rechazo 4xx, timeout y Circuit
 * Breaker. En todos los casos la notificación interna permanece intacta.
 */
class NotificationsHttpIntegrationTests extends AbstractIntegrationTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void notificationProperties(DynamicPropertyRegistry registry) {
        registry.add("notifications.provider", () -> "http");
        registry.add("notifications.http.base-url", () -> "http://localhost:" + WIREMOCK.port());
        registry.add("notifications.http.require-https", () -> "false");
        registry.add("notifications.http.timeout", () -> "1s"); // más corto que el límite de 10 s para no alargar la prueba
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @Autowired PublishNotificationUseCase publish;
    @Autowired TransactionTemplate tx;
    @Autowired ExternalNotificationGateway gateway;
    @Autowired CircuitBreakerRegistry registry;

    @BeforeEach
    void setUp() {
        WIREMOCK.resetAll();
        jdbc.update("DELETE FROM notifications");
        registry.circuitBreaker("notifications").reset();
    }

    @AfterEach
    void clearMdc() {
        MDC.remove("correlationId");
    }

    private void publish(String eventKey) {
        tx.executeWithoutResult(status -> publish.execute(new PublishNotificationCommand(RecipientType.BUYER, null,
                "ORDER_IN_PREPARATION", "Tu pedido está en preparación", "Estamos preparando tu pedido #42.", "ORDER",
                "42", eventKey)));
    }

    private void awaitStatus(String eventKey, String expected) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> expected.equals(
                jdbc.queryForObject("SELECT external_status FROM notifications WHERE event_key = ?", String.class, eventKey)));
    }

    private void stubStatus(int status) {
        WIREMOCK.stubFor(post(urlEqualTo("/notifications")).willReturn(aResponse().withStatus(status)));
    }

    @Test
    void httpProviderIsSelected() {
        assertThat(gateway).isInstanceOf(HttpExternalNotificationGateway.class);
    }

    @Test
    void successSendsTheContractHeadersAndBodyAndMarksTheNoticeSent() {
        MDC.put("correlationId", "http-corr-1");
        stubStatus(202);

        publish("order-42-IN_PREPARATION");

        awaitStatus("order-42-IN_PREPARATION", "SENT");
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/notifications"))
                .withHeader("Idempotency-Key", equalTo("order-42-IN_PREPARATION"))
                .withHeader("X-Correlation-Id", equalTo("http-corr-1"))
                .withRequestBody(matchingJsonPath("$.eventKey", equalTo("order-42-IN_PREPARATION")))
                .withRequestBody(matchingJsonPath("$.recipient.type", equalTo("BUYER")))
                .withRequestBody(matchingJsonPath("$.type", equalTo("ORDER_IN_PREPARATION")))
                .withRequestBody(matchingJsonPath("$.title", equalTo("Tu pedido está en preparación")))
                .withRequestBody(matchingJsonPath("$.reference.type", equalTo("ORDER")))
                .withRequestBody(matchingJsonPath("$.reference.id", equalTo("42"))));
    }

    @Test
    void serverErrorMarksTheNoticeFailedAndKeepsTheInternalNotification() {
        stubStatus(500);

        publish("order-42-IN_PREPARATION");

        awaitStatus("order-42-IN_PREPARATION", "FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM notifications", String.class)).contains("500");
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void timeoutMarksTheNoticeFailedAndKeepsTheInternalNotification() {
        WIREMOCK.stubFor(post(urlEqualTo("/notifications")).willReturn(aResponse().withStatus(202).withFixedDelay(3_000)));

        publish("order-42-IN_PREPARATION");

        awaitStatus("order-42-IN_PREPARATION", "FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM notifications", String.class)).contains("timeout");
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void definitiveRejectionMarksFailedAndDoesNotCountAgainstTheCircuit() {
        stubStatus(400);
        for (int i = 0; i < 6; i++) {
            publish("rejected-" + i);
            awaitStatus("rejected-" + i, "FAILED");
        }

        assertThat(registry.circuitBreaker("notifications").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        WIREMOCK.verify(6, postRequestedFor(urlEqualTo("/notifications")));
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndLaterNoticesFailFastWithoutCallingTheProvider() {
        stubStatus(500);
        for (int i = 0; i < 5; i++) {
            publish("failing-" + i);
            awaitStatus("failing-" + i, "FAILED");
        }
        assertThat(registry.circuitBreaker("notifications").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        publish("after-open");

        awaitStatus("after-open", "FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM notifications WHERE event_key = 'after-open'", String.class))
                .contains("no disponible temporalmente");
        WIREMOCK.verify(5, postRequestedFor(urlEqualTo("/notifications"))); // la sexta no llegó al proveedor
        assertThat(count("notifications")).isEqualTo(6); // ninguna notificación interna se perdió
    }
}
