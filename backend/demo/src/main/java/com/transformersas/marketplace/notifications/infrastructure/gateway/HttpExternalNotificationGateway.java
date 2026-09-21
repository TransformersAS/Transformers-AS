package com.transformersas.marketplace.notifications.infrastructure.gateway;

import com.transformersas.marketplace.notifications.domain.model.ExternalNotificationRejectedException;
import com.transformersas.marketplace.notifications.domain.model.ExternalNotificationUnavailableException;
import com.transformersas.marketplace.notifications.domain.repository.ExternalNotificationGateway;
import com.transformersas.marketplace.shared.http.ExternalRestClients;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adaptador HTTP del servicio externo de notificaciones. Contrato en docs/contracts/notifications-api.md.
 * Política: timeout máx. 10 s, Circuit Breaker "notifications" y ningún reintento automático (el aviso es
 * idempotente por Idempotency-Key = eventKey). 4xx es rechazo definitivo y no cuenta para el circuito.
 */
public class HttpExternalNotificationGateway implements ExternalNotificationGateway {

    public static final String CIRCUIT_BREAKER = "notifications";

    private static final Logger log = LoggerFactory.getLogger(HttpExternalNotificationGateway.class);

    private final RestClient client;
    private final CircuitBreaker breaker;

    HttpExternalNotificationGateway(RestClient client, CircuitBreaker breaker) {
        this.client = client;
        this.breaker = breaker;
    }

    public static HttpExternalNotificationGateway create(NotificationsHttpProperties properties,
                                                         RestClient.Builder builder, CircuitBreakerRegistry registry) {
        RestClient client = ExternalRestClients.build(builder, properties.baseUrl(), properties.timeout(),
                properties.requireHttps(), "notifications.http");
        return new HttpExternalNotificationGateway(client, registry.circuitBreaker(CIRCUIT_BREAKER));
    }

    @Override
    public void send(Request request) {
        try {
            breaker.executeRunnable(() -> post(request));
        } catch (CallNotPermittedException open) {
            log.warn("Circuito de notificaciones abierto: no se llama al proveedor eventKey={}", request.eventKey());
            throw new ExternalNotificationUnavailableException(
                    "Servicio externo de notificaciones no disponible temporalmente", open);
        }
    }

    private void post(Request request) {
        try {
            client.post().uri("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", request.eventKey())
                    .header("X-Correlation-Id", request.correlationId())
                    .body(Payload.from(request))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException rejected) {
            throw new ExternalNotificationRejectedException(
                    "El servicio de notificaciones rechazó el aviso (" + rejected.getStatusCode().value() + ")", rejected);
        } catch (HttpServerErrorException failure) {
            throw new ExternalNotificationUnavailableException(
                    "Error del servicio de notificaciones (" + failure.getStatusCode().value() + ")", failure);
        } catch (ResourceAccessException noResponse) {
            throw new ExternalNotificationUnavailableException(
                    "Sin respuesta del servicio de notificaciones (timeout o red)", noResponse);
        } catch (RestClientException invalid) {
            throw new ExternalNotificationUnavailableException("Respuesta inválida del servicio de notificaciones", invalid);
        }
    }

    record Payload(String eventKey, Recipient recipient, String type, String title, String message,
                   Reference reference) {

        static Payload from(Request request) {
            return new Payload(request.eventKey(),
                    new Recipient(request.recipientType().name(), request.recipientId()), request.type(),
                    request.title(), request.message(), new Reference(request.referenceType(), request.referenceId()));
        }

        record Recipient(String type, Long id) {
        }

        record Reference(String type, String id) {
        }
    }
}
