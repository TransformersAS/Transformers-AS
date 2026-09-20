package com.transformersas.marketplace.notifications.domain.repository;

import com.transformersas.marketplace.notifications.domain.model.RecipientType;

/** Puerto hacia el servicio externo de notificaciones. Contrato: docs/contracts/notifications-api.md. */
public interface ExternalNotificationGateway {

    /**
     * Solicita el aviso externo. Idempotente por eventKey.
     *
     * @throws com.transformersas.marketplace.notifications.domain.model.ExternalNotificationRejectedException rechazo definitivo
     * @throws com.transformersas.marketplace.notifications.domain.model.ExternalNotificationUnavailableException fallo temporal
     */
    void send(Request request);

    record Request(
            Long notificationId,
            String eventKey,
            RecipientType recipientType,
            Long recipientId,
            String type,
            String title,
            String message,
            String referenceType,
            String referenceId,
            String correlationId
    ) {
    }
}
