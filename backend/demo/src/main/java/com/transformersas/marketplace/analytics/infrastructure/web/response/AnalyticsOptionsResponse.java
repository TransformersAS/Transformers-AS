package com.transformersas.marketplace.analytics.infrastructure.web.response;

import com.transformersas.marketplace.analytics.domain.model.AnalyticsOptions;

import java.util.List;

/** Valores para los filtros de la analítica. hasCost avisa qué productos no tienen costo unitario. */
public record AnalyticsOptionsResponse(List<ProductOption> products, List<String> categories) {

    public record ProductOption(Long id, String name, String category, boolean hasCost) {
    }

    public static AnalyticsOptionsResponse from(AnalyticsOptions options) {
        return new AnalyticsOptionsResponse(options.products().stream()
                .map(product -> new ProductOption(product.id(), product.name(), product.category(), product.hasCost()))
                .toList(), options.categories());
    }
}
