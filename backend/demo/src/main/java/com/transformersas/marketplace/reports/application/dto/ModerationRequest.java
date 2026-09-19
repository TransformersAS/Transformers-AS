package com.transformersas.marketplace.reports.application.dto;

import com.transformersas.marketplace.reports.domain.model.ModerationDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Decisión de moderación (RF-159); {@code expectedVersion} evita resolver sobre una vista desactualizada. */
public record ModerationRequest(
        @NotNull(message = "La decisión es obligatoria") ModerationDecision decision,

        @NotBlank(message = "La justificación es obligatoria")
        @Size(min = 10, max = 4000, message = "La justificación debe tener entre 10 y 4000 caracteres")
        String justification,

        Long expectedVersion) {
}
