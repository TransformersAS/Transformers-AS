package com.transformersas.marketplace.product;

/** El id de contenido que usa moderación es texto; el de un producto es numérico. */
final class ProductIds {
    private ProductIds() {
    }

    /**
     * El id numérico, o null si el texto no lo es. Solo acepta la forma canónica ("1", no "01" ni "+1"): moderación
     * agrupa los reportes por el texto exacto del id, y dos formas del mismo producto abrirían casos distintos.
     */
    static Long parse(String contentId) {
        try {
            Long id = Long.valueOf(contentId);
            return String.valueOf(id).equals(contentId) ? id : null;
        } catch (NumberFormatException notAProductId) {
            return null;
        }
    }
}
