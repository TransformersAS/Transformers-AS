package com.transformersas.marketplace.analytics.domain.model;

import java.util.List;

/**
 * Valores posibles de los filtros: los productos de la tienda y sus categorías. hasCost avisa qué productos aún no
 * tienen costo unitario, sin el cual la ganancia no se puede calcular (precondición 3).
 */
public record AnalyticsOptions(List<ProductOption> products, List<String> categories) {

    public record ProductOption(Long id, String name, String category, boolean hasCost) {
    }
}
