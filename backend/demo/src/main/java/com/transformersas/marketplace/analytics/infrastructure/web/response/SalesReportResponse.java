package com.transformersas.marketplace.analytics.infrastructure.web.response;

import com.transformersas.marketplace.analytics.domain.model.IndicatorComparison;
import com.transformersas.marketplace.analytics.domain.model.SalesIndicators;
import com.transformersas.marketplace.analytics.domain.model.SalesReport;
import com.transformersas.marketplace.analytics.domain.model.SalesReport.PeriodReport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Analítica de ventas (CU-26). comparison y comparisons solo vienen si se pidió un periodo de comparación. */
public record SalesReportResponse(String storeName, Long productId, String productName, String category,
                                  String granularity, PeriodResponse current, PeriodResponse comparison,
                                  List<ComparisonResponse> comparisons) {

    public static SalesReportResponse from(SalesReport report) {
        return new SalesReportResponse(report.storeName(), report.filter().productId(), report.productName(),
                report.filter().category(), report.granularity().name(), PeriodResponse.from(report.current()),
                report.hasComparison() ? PeriodResponse.from(report.comparison()) : null,
                report.comparisons().stream().map(ComparisonResponse::from).toList());
    }

    /** hasData es falso si no hubo pedidos con esos filtros (A1, A3 y A4). */
    public record PeriodResponse(LocalDate from, LocalDate to, boolean hasData, IndicatorsResponse indicators,
                                 List<ProductResponse> topProducts, List<CategoryResponse> categories,
                                 List<PointResponse> series) {

        static PeriodResponse from(PeriodReport period) {
            return new PeriodResponse(period.period().from(), period.period().to(), period.indicators().hasData(),
                    IndicatorsResponse.from(period.indicators()),
                    period.topProducts().stream().map(product -> new ProductResponse(product.productId(),
                            product.name(), product.category(), product.units(), product.sales(), product.refunds(),
                            product.costs(), product.profit(), product.unitsWithoutCost())).toList(),
                    period.categories().stream().map(category -> new CategoryResponse(category.category(),
                            category.units(), category.sales())).toList(),
                    period.series().stream().map(point -> new PointResponse(point.start(), point.sales(),
                            point.costs(), point.orders())).toList());
        }
    }

    /** costComplete es falso si alguna unidad vendida no tiene costo: costs y profit son parciales. */
    public record IndicatorsResponse(BigDecimal sales, BigDecimal discounts, BigDecimal refunds,
                                     BigDecimal netRevenue, BigDecimal costs, BigDecimal profit,
                                     BigDecimal marginPercent, long orders, long units, BigDecimal averageTicket,
                                     long unitsWithoutCost, boolean costComplete) {

        static IndicatorsResponse from(SalesIndicators indicators) {
            return new IndicatorsResponse(indicators.sales(), indicators.discounts(), indicators.refunds(),
                    indicators.netRevenue(), indicators.costs(), indicators.profit(), indicators.marginPercent(),
                    indicators.orders(), indicators.units(), indicators.averageTicket(),
                    indicators.unitsWithoutCost(), indicators.costComplete());
        }
    }

    public record ProductResponse(Long productId, String name, String category, long units, BigDecimal sales,
                                  BigDecimal refunds, BigDecimal costs, BigDecimal profit, long unitsWithoutCost) {
    }

    public record CategoryResponse(String category, long units, BigDecimal sales) {
    }

    public record PointResponse(LocalDate start, BigDecimal sales, BigDecimal costs, long orders) {
    }

    /** percentChange es nulo cuando la base (el periodo de comparación) vale cero (A5). */
    public record ComparisonResponse(String indicator, BigDecimal current, BigDecimal previous, BigDecimal difference,
                                     BigDecimal percentChange, boolean percentAvailable) {

        static ComparisonResponse from(IndicatorComparison comparison) {
            return new ComparisonResponse(comparison.indicator(), comparison.current(), comparison.previous(),
                    comparison.difference(), comparison.percentChange(), comparison.percentAvailable());
        }
    }
}
