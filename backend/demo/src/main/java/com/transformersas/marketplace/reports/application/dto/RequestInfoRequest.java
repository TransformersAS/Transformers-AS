package com.transformersas.marketplace.reports.application.dto;

import com.transformersas.marketplace.reports.domain.model.InfoRequestTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Solicitud de información adicional (RF-158). {@code targetUserId} solo hace falta para elegir a
 * uno de varios reportadores; el propietario se resuelve desde el contenido.
 */
public record RequestInfoRequest(
        @NotNull(message = "El destinatario es obligatorio") InfoRequestTarget target,

        String targetUserId,

        @NotBlank(message = "El mensaje es obligatorio")
        @Size(max = 2000, message = "El mensaje no puede superar 2000 caracteres")
        String message) {
}
