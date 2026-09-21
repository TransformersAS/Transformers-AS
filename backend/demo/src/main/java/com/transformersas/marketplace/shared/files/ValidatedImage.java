package com.transformersas.marketplace.shared.files;

/**
 * Imagen que pasó la validación. contentType sale del contenido real (JPEG o PNG), no de lo que declaró el cliente.
 * sha256 identifica el contenido y sirve de ETag.
 */
public record ValidatedImage(String contentType, int width, int height, long sizeBytes, String sha256) {
}
