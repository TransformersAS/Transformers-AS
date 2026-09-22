package com.transformersas.marketplace.stock.dto;

/** Resultado de la carga masiva de productos: cuántos se crearon y, de ellos, cuántos quedaron publicados. */
public record ProductImportResult(int created, int published) {
}
