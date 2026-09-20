package com.transformersas.marketplace.notifications.application.usecase;

import com.transformersas.marketplace.notifications.domain.model.ExternalNotificationRejectedException;
import com.transformersas.marketplace.notifications.domain.model.ExternalNotificationUnavailableException;
import com.transformersas.marketplace.notifications.domain.model.ExternalStatus;
import com.transformersas.marketplace.notifications.domain.model.Notification;
import com.transformersas.marketplace.notifications.domain.repository.ExternalNotificationGateway;
import com.transformersas.marketplace.notifications.domain.repository.NotificationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Envía el aviso externo de una notificación ya confirmada y registra el resultado. NO es transaccional (la llamada
 * externa nunca ocurre dentro de una transacción de BD) y NUNCA lanza: cualquier fallo queda en external_status=FAILED
 * con su motivo, y la notificación interna permanece intacta (A7). Sin reintentos automáticos; attempts permite a un
 * barrido futuro reintentar los PENDING/FAILED.
 */
@Service
public class DispatchExternalNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(DispatchExternalNotificationUseCase.class);

    private final NotificationRepository notifications;
    private final ExternalNotificationGateway gateway;

    public DispatchExternalNotificationUseCase(NotificationRepository notifications,
                                               ExternalNotificationGateway gateway) {
        this.notifications = notifications;
        this.gateway = gateway;
    }

    public void execute(Long notificationId) {
        Notification notification = notifications.findById(notificationId).orElse(null);
        if (notification == null || notification.externalStatus() == ExternalStatus.SENT) {
            return; // Inexistente o ya enviada: el envío es idempotente.
        }
        try {
            gateway.send(new ExternalNotificationGateway.Request(notification.id(), notification.eventKey(),
                    notification.recipientType(), notification.recipientId(), notification.type(),
                    notification.title(), notification.message(), notification.referenceType(),
                    notification.referenceId(), notification.correlationId()));
            notifications.markSent(notificationId);
        } catch (ExternalNotificationRejectedException | ExternalNotificationUnavailableException failure) {
            log.warn("Aviso externo fallido notificationId={} motivo={}", notificationId, failure.getMessage());
            notifications.markFailed(notificationId, failure.getMessage());
        } catch (RuntimeException unexpected) {
            log.error("Error inesperado enviando aviso externo notificationId={}", notificationId, unexpected);
            notifications.markFailed(notificationId, "Error inesperado: " + unexpected.getClass().getSimpleName());
        }
    }
}
