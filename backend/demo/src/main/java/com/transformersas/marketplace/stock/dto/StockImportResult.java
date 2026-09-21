package com.transformersas.marketplace.stock.dto;

/** Resultado de la actualización masiva de inventario: cuántos movimientos (entradas y ajustes) se aplicaron. */
public record StockImportResult(int applied) {
}
