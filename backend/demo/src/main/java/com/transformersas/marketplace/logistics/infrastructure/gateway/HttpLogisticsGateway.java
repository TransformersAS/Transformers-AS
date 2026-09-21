package com.transformersas.marketplace.logistics.infrastructure.gateway;

import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnMethod;
import com.transformersas.marketplace.logistics.domain.model.ReturnReceipt;
import com.transformersas.marketplace.logistics.domain.model.ReturnRequestData;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.ShipmentReceipt;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvidence;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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
        ResponseEntity<Response> entity = call(() -> client.post().uri("/shipments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header(CorrelationContext.HEADER, CorrelationContext.current())
                .body(Payload.from(request))
                .retrieve()
                .toEntity(Response.class));

        int status = entity.getStatusCode().value();
        Response body = entity.getBody();
        if ((status != 200 && status != 201) || body == null || blank(body.shipmentId())
                || blank(body.trackingCode()) || !"CREATED".equals(body.status())) {
            throw new LogisticsUnavailableException("Respuesta inválida del servicio logístico", null);
        }
        return new ShipmentReceipt(body.shipmentId(), body.trackingCode());
    }

    @Override
    public List<ReturnMethod> fetchReturnMethods(Long orderId, Long storeId) {
        try {
            return breaker.executeSupplier(() -> {
                ResponseEntity<MethodsResponse> entity = call(() -> client.get()
                        .uri(uri -> uri.path("/returns/methods").queryParam("orderId", orderId)
                                .queryParam("storeId", storeId).build())
                        .header(CorrelationContext.HEADER, CorrelationContext.current())
                        .retrieve()
                        .toEntity(MethodsResponse.class));
                MethodsResponse body = entity.getBody();
                if (entity.getStatusCode().value() != 200 || body == null || body.methods() == null) {
                    throw new LogisticsUnavailableException("Respuesta inválida del servicio logístico", null);
                }
                return body.methods().stream().filter(method -> !blank(method.code()))
                        .map(method -> new ReturnMethod(method.code(), method.label() == null ? method.code()
                                : method.label())).toList();
            });
        } catch (CallNotPermittedException open) {
            log.warn("Circuito logístico abierto: no se consultan los métodos de retorno orderId={}", orderId);
            throw new LogisticsUnavailableException("Servicio logístico no disponible temporalmente", open);
        }
    }

    @Override
    public ReturnReceipt createReturn(ReturnRequestData request, String idempotencyKey) {
        try {
            return breaker.executeSupplier(() -> sendReturn(request, idempotencyKey));
        } catch (CallNotPermittedException open) {
            log.warn("Circuito logístico abierto: no se crea el retorno key={}", idempotencyKey);
            throw new LogisticsUnavailableException("Servicio logístico no disponible temporalmente", open);
        }
    }

    private ReturnReceipt sendReturn(ReturnRequestData request, String idempotencyKey) {
        ResponseEntity<ReturnResponse> entity = call(() -> client.post().uri("/returns")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header(CorrelationContext.HEADER, CorrelationContext.current())
                .body(ReturnPayload.from(request))
                .retrieve()
                .toEntity(ReturnResponse.class));

        int status = entity.getStatusCode().value();
        ReturnResponse body = entity.getBody();
        if ((status != 200 && status != 201) || body == null || blank(body.returnId())
                || blank(body.trackingCode()) || !"CREATED".equals(body.status())) {
            throw new LogisticsUnavailableException("Respuesta inválida del servicio logístico", null);
        }
        return new ReturnReceipt(body.returnId(), body.trackingCode());
    }

    @Override
    public List<TrackingUpdate> fetchShipmentUpdates(String providerShipmentId) {
        return fetchEvents("/shipments/{id}/events", providerShipmentId).stream()
                .map(event -> toShipmentUpdate(providerShipmentId, event)).flatMap(Optional::stream).toList();
    }

    @Override
    public List<ReturnTrackingUpdate> fetchReturnUpdates(String providerReturnId) {
        return fetchEvents("/returns/{id}/events", providerReturnId).stream()
                .map(event -> toReturnUpdate(providerReturnId, event)).flatMap(Optional::stream).toList();
    }

    /** Lectura de la línea de tiempo del proveedor: mismo timeout, circuito y clasificación de errores que crear. */
    private List<EventPayload> fetchEvents(String path, String reference) {
        try {
            return breaker.executeSupplier(() -> {
                ResponseEntity<EventsResponse> entity = call(() -> client.get().uri(path, reference)
                        .header(CorrelationContext.HEADER, CorrelationContext.current())
                        .retrieve()
                        .toEntity(EventsResponse.class));
                EventsResponse body = entity.getBody();
                if (entity.getStatusCode().value() != 200 || body == null || body.events() == null) {
                    throw new LogisticsUnavailableException("Respuesta inválida del servicio logístico", null);
                }
                return body.events();
            });
        } catch (CallNotPermittedException open) {
            log.warn("Circuito logístico abierto: no se consulta el seguimiento reference={}", reference);
            throw new LogisticsUnavailableException("Servicio logístico no disponible temporalmente", open);
        }
    }

    // Una actualización mal formada o de un tipo que no conocemos se omite: no debe tumbar la consulta completa.
    private Optional<TrackingUpdate> toShipmentUpdate(String shipmentId, EventPayload event) {
        try {
            var type = ShipmentEventType.parse(event.type());
            if (type.isEmpty()) {
                log.warn("Tipo de actualización de envío desconocido: {}", event.type());
                return Optional.empty();
            }
            return Optional.of(new TrackingUpdate(event.eventId(), shipmentId, event.trackingCode(), type.get(),
                    Instant.parse(event.occurredAt()), event.description(), event.location(), evidence(event)));
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException invalid) {
            log.warn("Actualización de envío inválida omitida eventId={}", event.eventId());
            return Optional.empty();
        }
    }

    private Optional<ReturnTrackingUpdate> toReturnUpdate(String returnId, EventPayload event) {
        try {
            var type = ReturnEventType.parse(event.type());
            if (type.isEmpty()) {
                log.warn("Tipo de actualización de retorno desconocido: {}", event.type());
                return Optional.empty();
            }
            return Optional.of(new ReturnTrackingUpdate(event.eventId(), returnId, event.trackingCode(), type.get(),
                    Instant.parse(event.occurredAt()), event.description(), event.location(), evidence(event)));
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException invalid) {
            log.warn("Actualización de retorno inválida omitida eventId={}", event.eventId());
            return Optional.empty();
        }
    }

    private static TrackingEvidence evidence(EventPayload event) {
        return event.evidence() == null ? null : new TrackingEvidence(event.evidence().type(),
                event.evidence().reference());
    }

    /** Ejecuta la llamada y traduce sus fallos: 4xx = rechazo definitivo; 5xx, red o cuerpo ilegible = temporal. */
    private <T> ResponseEntity<T> call(Supplier<ResponseEntity<T>> request) {
        try {
            return request.get();
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

    // Retorno de una devolución (CU-19): el destino es la tienda (solo su id); el servicio resuelve el lugar de entrega.
    record ReturnPayload(String returnReference, String orderReference, Long storeId, String method,
                         Payload.Party pickup, List<Payload.Line> items) {

        static ReturnPayload from(ReturnRequestData request) {
            var pickup = request.pickup();
            return new ReturnPayload("return-" + request.returnId(), "order-" + request.orderId(), request.storeId(),
                    request.methodCode(), new Payload.Party(pickup.name(), pickup.street(), pickup.city(),
                            pickup.department(), pickup.postalCode(), pickup.phone()),
                    request.items().stream().map(item -> new Payload.Line(item.name(), item.quantity())).toList());
        }
    }

    record ReturnResponse(String returnId, String trackingCode, String status) {
    }

    record MethodsResponse(List<MethodPayload> methods) {
    }

    record MethodPayload(String code, String label) {
    }

    record EventsResponse(List<EventPayload> events) {
    }

    record EventPayload(String eventId, String trackingCode, String type, String occurredAt, String description,
                        String location, EvidencePayload evidence) {
    }

    record EvidencePayload(String type, String reference) {
    }
}
