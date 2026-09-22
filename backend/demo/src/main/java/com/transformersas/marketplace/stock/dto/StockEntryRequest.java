package com.transformersas.marketplace.stock.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Entrada de mercancía: cuántas unidades llegaron (y, si se quiere, de dónde). */
public record StockEntryRequest(
        @NotNull @Min(1) @Max(1_000_000) Integer quantity,
        @Size(max = 255) String reason
) {
}
