package com.transformersas.marketplace.suspensions.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.time.LocalDateTime;

/**
 * Aclaración que el administrador pide al vendedor (paso 6). El vendedor tiene {@link #RESPONSE_HOURS} horas para
 * responder (paso 8); vencido el plazo el administrador sigue con lo que tiene (A2) y la respuesta ya no se admite.
 */
public record ClarificationRequest(Long id, Long storeId, Long requestedBy, String message,
                                   LocalDateTime requestedAt, LocalDateTime dueAt, Status status, String response,
                                   LocalDateTime respondedAt) {

    public static final int RESPONSE_HOURS = 72;

    /** OPEN: espera respuesta. ANSWERED: el vendedor respondió. EXPIRED: venció sin respuesta. */
    public enum Status { OPEN, ANSWERED, EXPIRED }

    public static ClarificationRequest ask(Long storeId, Long adminId, String message, LocalDateTime now) {
        String text = Texts.required(message, 10, 1000, "CLARIFICATION_MESSAGE_REQUIRED", "message", "El mensaje");
        return new ClarificationRequest(null, storeId, adminId, text, now, now.plusHours(RESPONSE_HOURS),
                Status.OPEN, null, null);
    }

    /** Estado que ven las personas: una abierta con el plazo vencido ya cuenta como vencida. */
    public Status effectiveStatus(LocalDateTime now) {
        return status == Status.OPEN && now.isAfter(dueAt) ? Status.EXPIRED : status;
    }

    /** Valida que el vendedor aún pueda responder y devuelve la respuesta normalizada. */
    public String acceptResponse(String text, LocalDateTime now) {
        if (status == Status.ANSWERED) {
            throw BusinessException.conflict("CLARIFICATION_ALREADY_ANSWERED", "Esta aclaración ya tiene respuesta");
        }
        if (effectiveStatus(now) == Status.EXPIRED) {
            throw BusinessException.conflict("CLARIFICATION_EXPIRED",
                    "Venció el plazo de " + RESPONSE_HOURS + " horas para responder esta aclaración");
        }
        return Texts.required(text, 1, 2000, "CLARIFICATION_RESPONSE_REQUIRED", "response", "La respuesta");
    }
}
