package com.transformersas.marketplace.reports.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Remisión a CU-22 cuando el caso justifica una sanción a nivel de cuenta. */
public record ReferralRequest(
        @NotBlank(message = "La justificación es obligatoria")
        @Size(min = 10, max = 4000, message = "La justificación debe tener entre 10 y 4000 caracteres")
        String justification) {
}
