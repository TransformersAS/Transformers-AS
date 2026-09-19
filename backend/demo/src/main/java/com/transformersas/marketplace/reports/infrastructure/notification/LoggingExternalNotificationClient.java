package com.transformersas.marketplace.reports.infrastructure.notification;

import com.transformersas.marketplace.reports.domain.port.ExternalNotificationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cliente por defecto mientras no exista el servicio externo real: no envía nada, solo deja
 * constancia en el log (sin el contenido del mensaje). Reemplazarlo por la integración real.
 */
@Component
class LoggingExternalNotificationClient implements ExternalNotificationClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingExternalNotificationClient.class);

    @Override
    public void send(String recipientId, String template, String payloadJson) {
        log.warn("Servicio externo de notificaciones no configurado: no se envió '{}' a '{}'.", template,
                recipientId);
    }
}
