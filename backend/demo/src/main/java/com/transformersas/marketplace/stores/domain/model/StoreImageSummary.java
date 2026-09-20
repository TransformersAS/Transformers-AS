package com.transformersas.marketplace.stores.domain.model;

/** Datos de una imagen sin su contenido. sha256 identifica el contenido y sirve de ETag y de versión de la URL. */
public record StoreImageSummary(StoreImageKind kind, String contentType, long sizeBytes, String sha256) {
}
