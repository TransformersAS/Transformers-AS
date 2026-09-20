package com.transformersas.marketplace.notifications.domain.repository;

import com.transformersas.marketplace.notifications.domain.model.Notification;

import java.util.Optional;

public interface NotificationRepository {

    /**
     * Inserta la notificación o, si ya existía una con el mismo eventKey, devuelve la existente sin duplicar.
     * created indica si esta llamada fue la que la insertó.
     */
    Insertion insertIfAbsent(Notification notification);

    Optional<Notification> findById(Long id);

    /** Marca el aviso externo como enviado y cuenta el intento. */
    void markSent(Long id);

    /** Marca el aviso externo como fallido, guarda el motivo (truncado) y cuenta el intento. */
    void markFailed(Long id, String error);

    record Insertion(Notification notification, boolean created) {
    }
}
