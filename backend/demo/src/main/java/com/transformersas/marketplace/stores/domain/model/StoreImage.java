package com.transformersas.marketplace.stores.domain.model;

/**
 * Imagen de una tienda con su contenido. Solo se carga al servirla: la configuración de la tienda usa
 * StoreImageSummary para no arrastrar los binarios.
 */
public record StoreImage(StoreImageKind kind, String contentType, long sizeBytes, String sha256, byte[] data) {

    @Override
    public boolean equals(Object other) {
        return other instanceof StoreImage image && kind == image.kind && contentType.equals(image.contentType)
                && sizeBytes == image.sizeBytes && sha256.equals(image.sha256);
    }

    @Override
    public int hashCode() {
        return sha256.hashCode();
    }

    @Override
    public String toString() {
        return "StoreImage[kind=" + kind + ", contentType=" + contentType + ", sizeBytes=" + sizeBytes + "]";
    }
}
