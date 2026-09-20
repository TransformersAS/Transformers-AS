package com.transformersas.marketplace.notifications.domain.model;

import java.time.LocalDateTime;

/**
 * Notificación interna. recipientId es nulo cuando aún no se conoce la cuenta destinataria (p. ej. comprador sin
 * autenticación por usuario). El contenido nunca debe llevar datos personales.
 */
public record Notification(
        Long id,
        RecipientType recipientType,
        Long recipientId,
        String type,
        String title,
        String message,
        String referenceType,
        String referenceId,
        String eventKey,
        ExternalStatus externalStatus,
        int attempts,
        String lastError,
        String correlationId,
        LocalDateTime createdAt
) {
}
