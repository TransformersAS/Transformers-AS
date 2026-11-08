package com.transformersas.marketplace.analytics.domain.repository;

import com.transformersas.marketplace.analytics.domain.model.AnalyticsFilter;
import com.transformersas.marketplace.analytics.domain.model.AnalyticsOptions;
import com.transformersas.marketplace.analytics.domain.model.AnalyticsPeriod;
import com.transformersas.marketplace.analytics.domain.model.Granularity;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.CategorySales;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.ProductSales;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.SeriesPoint;
import com.transformersas.marketplace.analytics.domain.model.SalesTotals;

import java.util.List;
import java.util.Optional;

/**
 * Lectura de las ventas de una tienda. Toda consulta parte de los pedidos de esa tienda creados dentro del periodo y
 * que no están cancelados (postcondición 2), y aplica el filtro a sus líneas.
 */
public interface SalesDataQuery {

    /** Totales del periodo, con los reembolsos completados de esos pedidos ya sumados. */
    SalesTotals totals(Long storeId, AnalyticsPeriod period, AnalyticsFilter filter);

    /** Productos más vendidos por unidades; a igual cantidad, por ventas. */
    List<ProductSales> topProducts(Long storeId, AnalyticsPeriod period, AnalyticsFilter filter, int limit);

    /** Ventas por categoría, de mayor a menor. */
    List<CategorySales> categories(Long storeId, AnalyticsPeriod period, AnalyticsFilter filter);

    /** Ventas, costos y pedidos de cada grupo con datos, en orden cronológico. */
    List<SeriesPoint> series(Long storeId, AnalyticsPeriod period, AnalyticsFilter filter, Granularity granularity);

    /** Nombre del producto si pertenece a la tienda; vacío si no existe o es de otra tienda. */
    Optional<String> productName(Long storeId, Long productId);

    AnalyticsOptions options(Long storeId);
}
