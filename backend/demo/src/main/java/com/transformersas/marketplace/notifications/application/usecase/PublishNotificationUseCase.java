/** Casos de uso de notificaciones. */
package com.transformersas.marketplace.notifications.application.usecase;

import com.transformersas.marketplace.notifications.application.dto.NotificationPublished;
import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.domain.model.ExternalStatus;
import com.transformersas.marketplace.notifications.domain.model.Notification;
import com.transformersas.marketplace.notifications.domain.repository.NotificationRepository;
import com.transformersas.marketplace.shared.web.CorrelationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Publica una notificación interna (RF-123). Exige una transacción abierta (MANDATORY): la notificación se guarda
 * junto con el cambio de negocio que la origina, de modo que ambos se confirman o se revierten a la vez. El aviso
 * externo NO se envía aquí: se despacha después del commit (AfterCommitNotificationDispatcher), así un rollback nunca
 * produce un aviso externo y un fallo externo nunca revierte ni oculta la notificación interna.
 */
@Service
public class PublishNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(PublishNotificationUseCase.class);

    private final NotificationRepository notifications;
    private final ApplicationEventPublisher events;

    public PublishNotificationUseCase(NotificationRepository notifications, ApplicationEventPublisher events) {
        this.notifications = notifications;
        this.events = events;
    }

    /** Devuelve la notificación; si el eventKey ya existía devuelve la existente y no vuelve a avisar. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Notification execute(PublishNotificationCommand command) {
        String correlationId = CorrelationContext.current();
        LocalDateTime now = LocalDateTime.now();

        var insertion = notifications.insertIfAbsent(new Notification(null, command.recipientType(),
                command.recipientId(), command.type(), command.title(), command.message(), command.referenceType(),
                command.referenceId(), command.eventKey(), ExternalStatus.PENDING, 0, null, correlationId, now));

        if (insertion.created()) {
            events.publishEvent(new NotificationPublished(insertion.notification().id(), correlationId));
            log.info("Notificación registrada type={} reference={}:{}", command.type(), command.referenceType(),
                    command.referenceId());
        } else {
            log.info("Notificación duplicada omitida eventKey={}", command.eventKey());
        }
        return insertion.notification();
    }
}
