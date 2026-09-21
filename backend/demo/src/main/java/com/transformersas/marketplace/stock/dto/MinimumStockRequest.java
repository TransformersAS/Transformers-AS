package com.transformersas.marketplace.stock.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Nivel mínimo de un producto; 0 desactiva el aviso. */
public record MinimumStockRequest(@NotNull @Min(0) @Max(1_000_000) Integer minStock) {
}
