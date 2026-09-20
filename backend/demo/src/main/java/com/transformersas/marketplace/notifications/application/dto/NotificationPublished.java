package com.transformersas.marketplace.notifications.application.dto;

/** Evento de aplicación: la notificación quedó guardada en la transacción actual. El aviso externo sale tras el commit. */
public record NotificationPublished(Long notificationId, String correlationId) {
}
