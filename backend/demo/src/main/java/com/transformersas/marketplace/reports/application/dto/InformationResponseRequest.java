package com.transformersas.marketplace.reports.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InformationResponseRequest(
        @NotBlank(message = "La respuesta es obligatoria")
        @Size(max = 4000, message = "La respuesta no puede superar 4000 caracteres")
        String text) {
}
