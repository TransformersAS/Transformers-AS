package com.transformersas.marketplace.analytics.domain.model;

/**
 * Filtros opcionales: un producto de la tienda y/o una categoría. Una categoría en blanco equivale a no filtrar. Los
 * productos que ya no existen se agrupan en {@link #NO_CATEGORY}.
 */
public record AnalyticsFilter(Long productId, String category) {

    public static final String NO_CATEGORY = "Sin categoría";

    public AnalyticsFilter {
        category = category == null || category.isBlank() ? null : category.strip();
    }

    public static AnalyticsFilter none() {
        return new AnalyticsFilter(null, null);
    }

    public boolean isEmpty() {
        return productId == null && category == null;
    }
}
