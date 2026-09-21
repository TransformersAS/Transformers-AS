package com.transformersas.marketplace.stock.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Ajuste: el conteo real que tiene el vendedor. El motivo es obligatorio porque un ajuste corrige el sistema. */
public record StockAdjustmentRequest(
        @NotNull @Min(0) @Max(1_000_000) Integer newStock,
        @NotBlank @Size(max = 255) String reason
) {
}
