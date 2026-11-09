package com.transformersas.marketplace.analytics.application.usecase;

import com.transformersas.marketplace.analytics.application.dto.AnalyticsQuery;
import com.transformersas.marketplace.analytics.domain.model.AnalyticsFilter;
import com.transformersas.marketplace.analytics.domain.model.AnalyticsPeriod;
import com.transformersas.marketplace.analytics.domain.model.Granularity;
import com.transformersas.marketplace.analytics.domain.model.IndicatorComparison;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.SeriesPoint;
import com.transformersas.marketplace.analytics.domain.model.SalesIndicators;
import com.transformersas.marketplace.analytics.domain.model.SalesReport;
import com.transformersas.marketplace.analytics.domain.model.SalesReport.PeriodReport;
import com.transformersas.marketplace.analytics.domain.repository.SalesDataQuery;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.usecase.GetStoreOperationalStatusUseCase;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Calcula la analítica de la tienda del vendedor (RF-124, RF-125, RF-197). Solo lee, en una transacción de solo
 * lectura: los dos periodos ven la misma foto de los datos.
 */
@Service
public class AnalyzeSalesUseCase {

    static final int TOP_PRODUCTS = 10;

    private final SalesDataQuery sales;
    private final GetStoreOperationalStatusUseCase stores;

    public AnalyzeSalesUseCase(SalesDataQuery sales, GetStoreOperationalStatusUseCase stores) {
        this.sales = sales;
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public SalesReport execute(Long storeId, AnalyticsQuery query) {
        LocalDate today = LocalDate.now();
        AnalyticsPeriod period = AnalyticsPeriod.of(query.from(), query.to(), today, "period");
        AnalyticsPeriod comparisonPeriod = query.wantsComparison()
                ? AnalyticsPeriod.of(query.compareFrom(), query.compareTo(), today, "comparison") : null;
        AnalyticsFilter filter = new AnalyticsFilter(query.productId(), query.category());
        // Un producto de otra tienda responde como inexistente: no revela qué vende otra tienda.
        String productName = filter.productId() == null ? null : sales.productName(storeId, filter.productId())
                .orElseThrow(() -> BusinessException.notFound("PRODUCT_NOT_FOUND", "El producto no pertenece a tu tienda"));
        String storeName = stores.execute(storeId)
                .orElseThrow(() -> BusinessException.notFound("STORE_NOT_FOUND", "La tienda no existe")).name();

        // La comparación usa la agrupación del periodo principal para que las dos series sean comparables.
        Granularity granularity = Granularity.forDays(period.days());
        PeriodReport current = report(storeId, period, filter, granularity);
        PeriodReport comparison = comparisonPeriod == null ? null
                : report(storeId, comparisonPeriod, filter, granularity);
        List<IndicatorComparison> comparisons = comparison == null ? List.of()
                : IndicatorComparison.between(current.indicators(), comparison.indicators());
        return new SalesReport(storeId, storeName, filter, productName, granularity, current, comparison, comparisons);
    }

    private PeriodReport report(Long storeId, AnalyticsPeriod period, AnalyticsFilter filter,
                                Granularity granularity) {
        SalesIndicators indicators = SalesIndicators.from(sales.totals(storeId, period, filter));
        return new PeriodReport(period, indicators, sales.topProducts(storeId, period, filter, TOP_PRODUCTS),
                sales.categories(storeId, period, filter),
                withEmptyBuckets(period, granularity, sales.series(storeId, period, filter, granularity)));
    }

    /** Completa con ceros los grupos sin ventas, del primero al último del periodo. */
    static List<SeriesPoint> withEmptyBuckets(AnalyticsPeriod period, Granularity granularity,
                                              List<SeriesPoint> points) {
        Map<LocalDate, SeriesPoint> byStart = points.stream()
                .collect(Collectors.toMap(SeriesPoint::start, Function.identity()));
        List<SeriesPoint> filled = new ArrayList<>();
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        for (LocalDate start = granularity.bucketOf(period.from()); !start.isAfter(period.to());
             start = granularity.next(start)) {
            filled.add(byStart.getOrDefault(start, new SeriesPoint(start, zero, zero, 0)));
        }
        return filled;
    }
}
