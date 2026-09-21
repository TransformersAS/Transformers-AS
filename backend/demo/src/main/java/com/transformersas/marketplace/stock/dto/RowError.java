package com.transformersas.marketplace.stock.dto;

/** Un error de una carga de Excel: el número de fila tal como se ve en la hoja (la 1 es el encabezado) y qué falló. */
public record RowError(int row, String message) {
}
