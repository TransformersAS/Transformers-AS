package com.transformersas.marketplace.reports.domain.port;

/** Puerto hacia el servicio externo de notificaciones; cualquier excepción cuenta como fallo de envío. */
public interface ExternalNotificationClient {

    void send(String recipientId, String template, String payloadJson);
}
