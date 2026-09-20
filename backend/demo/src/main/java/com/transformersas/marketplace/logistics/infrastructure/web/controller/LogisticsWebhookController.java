package com.transformersas.marketplace.logistics.infrastructure.web.controller;

import com.transformersas.marketplace.logistics.application.usecase.ProcessReturnUpdateUseCase;
import com.transformersas.marketplace.logistics.application.usecase.ProcessShipmentUpdateUseCase;
import com.transformersas.marketplace.logistics.domain.model.TrackingSource;
import com.transformersas.marketplace.logistics.infrastructure.web.request.TrackingEventRequest;
import com.transformersas.marketplace.logistics.infrastructure.web.response.WebhookResponse;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Actualizaciones que el servicio logístico externo empuja al marketplace (CU-24 y CU-25). No hay sesión: el
 * proveedor se autentica con la firma HMAC del cuerpo. Adaptador HTTP delgado: verifica la firma, lee el JSON y
 * delega en un único caso de uso. Se recibe el cuerpo en bruto porque la firma se calcula sobre esos bytes.
 */
@RestController
@RequestMapping("/api/logistics/webhooks")
public class LogisticsWebhookController {

    private final WebhookSignatureVerifier signature;
    private final ObjectMapper json;
    private final ProcessShipmentUpdateUseCase processShipment;
    private final ProcessReturnUpdateUseCase processReturn;

    public LogisticsWebhookController(WebhookSignatureVerifier signature, ObjectMapper json,
                                      ProcessShipmentUpdateUseCase processShipment,
                                      ProcessReturnUpdateUseCase processReturn) {
        this.signature = signature;
        this.json = json;
        this.processShipment = processShipment;
        this.processReturn = processReturn;
    }

    @PostMapping("/shipments")
    public WebhookResponse shipment(@RequestHeader(name = WebhookSignatureVerifier.HEADER, required = false)
                                    String signatureHeader, @RequestBody byte[] body) {
        signature.verify(body, signatureHeader);
        return parse(body).toShipmentUpdate()
                .map(update -> new WebhookResponse(processShipment.execute(update, TrackingSource.WEBHOOK).name()))
                .orElseGet(() -> new WebhookResponse("IGNORED"));
    }

    @PostMapping("/returns")
    public WebhookResponse returned(@RequestHeader(name = WebhookSignatureVerifier.HEADER, required = false)
                                    String signatureHeader, @RequestBody byte[] body) {
        signature.verify(body, signatureHeader);
        return parse(body).toReturnUpdate()
                .map(update -> new WebhookResponse(processReturn.execute(update, TrackingSource.WEBHOOK).name()))
                .orElseGet(() -> new WebhookResponse("IGNORED"));
    }

    private TrackingEventRequest parse(byte[] body) {
        try {
            TrackingEventRequest request = json.readValue(body, TrackingEventRequest.class);
            if (request == null) {
                throw BusinessException.invalid("INVALID_WEBHOOK_PAYLOAD", "El cuerpo del webhook está vacío");
            }
            return request;
        } catch (JacksonException unreadable) {
            throw BusinessException.invalid("INVALID_WEBHOOK_PAYLOAD", "El cuerpo del webhook no es un JSON válido");
        }
    }
}
