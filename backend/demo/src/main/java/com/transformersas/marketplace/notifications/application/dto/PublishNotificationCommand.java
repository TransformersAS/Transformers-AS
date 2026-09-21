package com.transformersas.marketplace.notifications.application.dto;

import com.transformersas.marketplace.notifications.domain.model.RecipientType;

/**
 * Datos de una notificación. eventKey identifica el evento de negocio (p. ej. "order-42-IN_PREPARATION"): publicar
 * dos veces el mismo eventKey produce una sola notificación.
 */
public record PublishNotificationCommand(
        RecipientType recipientType,
        Long recipientId,
        String type,
        String title,
        String message,
        String referenceType,
        String referenceId,
        String eventKey
) {
    public PublishNotificationCommand {
        if (recipientType == null) {
            throw new IllegalArgumentException("recipientType es obligatorio");
        }
        require(type, 64, "type");
        require(title, 200, "title");
        require(message, 1000, "message");
        require(referenceType, 32, "referenceType");
        require(referenceId, 64, "referenceId");
        require(eventKey, 150, "eventKey");
    }

    private static void require(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " es obligatorio y admite hasta " + max + " caracteres");
        }
    }
}
