package com.transformersas.marketplace.analytics.domain.model;

import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.CategorySales;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.ProductSales;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.SeriesPoint;

import java.util.List;

/**
 * Resultado de la analítica: el periodo principal y, si se pidió, el de comparación con las diferencias de cada
 * indicador. Ambos periodos usan los mismos filtros y la misma agrupación de la serie (RF-125). productName es el
 * nombre del producto filtrado, o nulo sin ese filtro.
 */
public record SalesReport(Long storeId, String storeName, AnalyticsFilter filter, String productName,
                          Granularity granularity,
                          PeriodReport current, PeriodReport comparison, List<IndicatorComparison> comparisons) {

    /** Lo que se calculó para un periodo. La serie incluye los grupos sin ventas, en cero, para no cortar la gráfica. */
    public record PeriodReport(AnalyticsPeriod period, SalesIndicators indicators, List<ProductSales> topProducts,
                               List<CategorySales> categories, List<SeriesPoint> series) {
    }

    public boolean hasComparison() {
        return comparison != null;
    }
}
