package com.transformersas.marketplace.logistics.infrastructure.gateway;

import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ShipmentReceipt;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;
import com.transformersas.marketplace.shared.http.ExternalRestClients;
import com.transformersas.marketplace.shared.web.CorrelationContext;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Adaptador HTTP del servicio logístico externo. Contrato en docs/contracts/logistics-api.md.
 *
 * <p>Política de resiliencia: timeout de conexión y respuesta (máx. 10 s, RNF-042), Circuit Breaker "logistics"
 * (RNF-045) y CERO reintentos automáticos: el reintento lo decide el vendedor (POST shipment) y es seguro por la
 * Idempotency-Key. 4xx es rechazo definitivo y no cuenta como fallo del circuito; 5xx, timeout, red, respuesta
 * inválida o circuito abierto son fallos temporales.
 */
public class HttpLogisticsGateway implements LogisticsGateway {

    public static final String CIRCUIT_BREAKER = "logistics";

    private static final Logger log = LoggerFactory.getLogger(HttpLogisticsGateway.class);

    private final RestClient client;
    private final CircuitBreaker breaker;

    HttpLogisticsGateway(RestClient client, CircuitBreaker breaker) {
        this.client = client;
        this.breaker = breaker;
    }

    public static HttpLogisticsGateway create(LogisticsHttpProperties properties, RestClient.Builder builder,
                                              CircuitBreakerRegistry registry) {
        RestClient client = ExternalRestClients.build(builder, properties.baseUrl(), properties.timeout(),
                properties.requireHttps(), "logistics.http");
        return new HttpLogisticsGateway(client, registry.circuitBreaker(CIRCUIT_BREAKER));
    }

    @Override
    public ShipmentReceipt createShipment(ShipmentRequest request, String idempotencyKey) {
        try {
            return breaker.executeSupplier(() -> send(request, idempotencyKey));
        } catch (CallNotPermittedException open) {
            log.warn("Circuito logístico abierto: no se llama al proveedor key={}", idempotencyKey);
            throw new LogisticsUnavailableException("Servicio logístico no disponible temporalmente", open);
        }
    }

    private ShipmentReceipt send(ShipmentRequest request, String idempotencyKey) {
        ResponseEntity<Response> entity;
        try {
            entity = client.post().uri("/shipments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .header(CorrelationContext.HEADER, CorrelationContext.current())
                    .body(Payload.from(request))
                    .retrieve()
                    .toEntity(Response.class);
        } catch (HttpClientErrorException rejected) {
            throw new LogisticsRejectedException(
                    "El servicio logístico rechazó la solicitud (" + rejected.getStatusCode().value() + ")", rejected);
        } catch (HttpServerErrorException failure) {
            throw new LogisticsUnavailableException(
                    "Error del servicio logístico (" + failure.getStatusCode().value() + ")", failure);
        } catch (ResourceAccessException noResponse) {
            throw new LogisticsUnavailableException("Sin respuesta del servicio logístico (timeout o red)", noResponse);
        } catch (RestClientException invalid) {
            throw new LogisticsUnavailableException("Respuesta inválida del servicio logístico", invalid);
        }

        int status = entity.getStatusCode().value();
        Response body = entity.getBody();
        if ((status != 200 && status != 201) || body == null || blank(body.shipmentId())
                || blank(body.trackingCode()) || !"CREATED".equals(body.status())) {
            throw new LogisticsUnavailableException("Respuesta inválida del servicio logístico", null);
        }
        return new ShipmentReceipt(body.shipmentId(), body.trackingCode());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // Cuerpo del contrato: no se registra en logs porque contiene datos personales.
    record Payload(String orderReference, String shippingMethod, Party recipient, List<Line> items) {

        static Payload from(ShipmentRequest request) {
            var recipient = request.recipient();
            return new Payload("order-" + request.orderId(), request.shippingMethod(),
                    new Party(recipient.name(), recipient.street(), recipient.city(), recipient.department(),
                            recipient.postalCode(), recipient.phone()),
                    request.items().stream().map(item -> new Line(item.name(), item.quantity())).toList());
        }

        record Party(String name, String street, String city, String department, String postalCode, String phone) {
        }

        record Line(String name, int quantity) {
        }
    }

    record Response(String shipmentId, String trackingCode, String status) {
    }
}
