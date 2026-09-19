package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.domain.model.NotificationChannel;
import com.transformersas.marketplace.reports.domain.model.NotificationStatus;
import com.transformersas.marketplace.reports.domain.port.ExternalNotificationClient;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationNotificationEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Envía el outbox al servicio externo con reintentos y espera exponencial (1, 2, 4, 8 min...) y
 * marca FALLIDA tras {@code MAX_ATTEMPTS} intentos. La llamada externa se hace fuera de cualquier
 * transacción de base de datos.
 */
@Service
public class NotificationDispatcher {

    static final int MAX_ATTEMPTS = 5;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(60);
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final ModerationNotificationRepository repository;
    private final ExternalNotificationClient client;
    private final Clock clock;

    public NotificationDispatcher(ModerationNotificationRepository repository, ExternalNotificationClient client,
                                  Clock clock) {
        this.repository = repository;
        this.client = client;
        this.clock = clock;
    }

    /** Procesa las notificaciones pendientes cuyo turno llegó; devuelve cuántas se enviaron. */
    public int dispatchDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ModerationNotificationEntity> due = repository
                .findTop50ByChannelAndStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                        NotificationChannel.EXTERNAL, NotificationStatus.PENDIENTE, now);
        int sent = 0;
        for (ModerationNotificationEntity notification : due) {
            try {
                client.send(notification.getRecipientId(), notification.getTemplate(), notification.getPayload());
                notification.setStatus(NotificationStatus.ENVIADA);
                notification.setSentAt(now);
                notification.setNextAttemptAt(null);
                notification.setLastError(null);
                notification.setAttempts(notification.getAttempts() + 1);
                sent++;
            } catch (RuntimeException failure) {
                registerFailure(notification, failure, now);
            }
            repository.save(notification);
        }
        return sent;
    }

    private void registerFailure(ModerationNotificationEntity notification, RuntimeException failure,
                                 LocalDateTime now) {
        int attempts = notification.getAttempts() + 1;
        notification.setAttempts(attempts);
        notification.setLastError(truncate(failure.getClass().getSimpleName() + ": " + failure.getMessage()));
        if (attempts >= MAX_ATTEMPTS) {
            notification.setStatus(NotificationStatus.FALLIDA);
            notification.setNextAttemptAt(null);
            log.error("Notificación {} marcada como FALLIDA tras {} intentos.", notification.getId(), attempts);
            return;
        }
        Duration backoff = Duration.ofMinutes(1L << (attempts - 1));
        notification.setNextAttemptAt(now.plus(backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff));
    }

    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
