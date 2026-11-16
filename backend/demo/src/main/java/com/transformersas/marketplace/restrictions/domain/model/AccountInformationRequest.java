package com.transformersas.marketplace.restrictions.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.time.LocalDateTime;

/**
 * Aclaración que el administrador pide a la persona dueña de la cuenta antes de decidir una restricción (A3). La
 * persona tiene {@link #RESPONSE_HOURS} horas para responder (A4); vencido el plazo el administrador sigue con lo
 * que tiene y la respuesta ya no se admite.
 */
public record AccountInformationRequest(Long id, Long accountId, Long requestedBy, String message,
                                        LocalDateTime requestedAt, LocalDateTime dueAt, Status status,
                                        String response, LocalDateTime respondedAt) {

    public static final int RESPONSE_HOURS = 72;

    /** OPEN: espera respuesta. ANSWERED: la persona respondió. EXPIRED: venció sin respuesta. */
    public enum Status { OPEN, ANSWERED, EXPIRED }

    public static AccountInformationRequest ask(Long accountId, Long adminId, String message, LocalDateTime now) {
        String text = Texts.required(message, 10, 1000, "INFORMATION_MESSAGE_REQUIRED", "message", "El mensaje");
        return new AccountInformationRequest(null, accountId, adminId, text, now, now.plusHours(RESPONSE_HOURS),
                Status.OPEN, null, null);
    }

    /** Estado que ve la persona: una abierta con el plazo vencido ya cuenta como vencida. */
    public Status effectiveStatus(LocalDateTime now) {
        return status == Status.OPEN && now.isAfter(dueAt) ? Status.EXPIRED : status;
    }

    /** Valida que la persona aún pueda responder y devuelve la respuesta normalizada. */
    public String acceptResponse(String text, LocalDateTime now) {
        if (status == Status.ANSWERED) {
            throw BusinessException.conflict("INFORMATION_ALREADY_ANSWERED", "Esta solicitud ya tiene respuesta");
        }
        if (effectiveStatus(now) == Status.EXPIRED) {
            throw BusinessException.conflict("INFORMATION_EXPIRED",
                    "Venció el plazo de " + RESPONSE_HOURS + " horas para responder esta solicitud");
        }
        return Texts.required(text, 1, 2000, "INFORMATION_RESPONSE_REQUIRED", "response", "La respuesta");
    }
}
