package com.transformersas.marketplace.analytics;

import com.transformersas.marketplace.analytics.application.usecase.AnalyzeSalesUseCaseAccess;
import com.transformersas.marketplace.analytics.domain.model.AnalyticsFilter;
import com.transformersas.marketplace.analytics.domain.model.AnalyticsPeriod;
import com.transformersas.marketplace.analytics.domain.model.Granularity;
import com.transformersas.marketplace.analytics.domain.model.IndicatorComparison;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.SeriesPoint;
import com.transformersas.marketplace.analytics.domain.model.SalesIndicators;
import com.transformersas.marketplace.analytics.domain.model.SalesReport;
import com.transformersas.marketplace.analytics.domain.model.SalesTotals;
import com.transformersas.marketplace.analytics.domain.repository.SalesReportExporter;
import com.transformersas.marketplace.analytics.infrastructure.export.PoiSalesReportExporter;
import com.transformersas.marketplace.analytics.infrastructure.web.controller.SellerAnalyticsController;
import com.transformersas.marketplace.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reglas de cálculo de CU-26 que no necesitan base de datos. */
class AnalyticsDomainTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Test
    void indicatorsWithoutSalesAreZeroAndMarginIsNotCalculable() {
        SalesIndicators empty = SalesIndicators.from(SalesTotals.empty());
        assertThat(empty.hasData()).isFalse();
        assertThat(empty.sales()).isEqualByComparingTo("0.00");
        assertThat(empty.averageTicket()).isEqualByComparingTo("0.00");
        assertThat(empty.marginPercent()).isNull();
        assertThat(empty.costComplete()).isTrue();
    }

    @Test
    void refundsLargerThanSalesLeaveNegativeProfitAndNoMargin() {
        SalesIndicators indicators = SalesIndicators.from(new SalesTotals(new BigDecimal("100"), BigDecimal.ZERO,
                new BigDecimal("150"), new BigDecimal("40"), 1, 1, 0));
        assertThat(indicators.netRevenue()).isEqualByComparingTo("-50.00");
        assertThat(indicators.profit()).isEqualByComparingTo("-90.00");
        assertThat(indicators.marginPercent()).isNull();
    }

    @Test
    void percentChangeUsesTheComparisonPeriodAsBase() {
        IndicatorComparison growth = IndicatorComparison.of("sales", new BigDecimal("150"), new BigDecimal("120"));
        assertThat(growth.difference()).isEqualByComparingTo("30");
        assertThat(growth.percentChange()).isEqualByComparingTo("25.0");
        IndicatorComparison drop = IndicatorComparison.of("profit", new BigDecimal("-30"), new BigDecimal("-60"));
        assertThat(drop.percentChange()).isEqualByComparingTo("50.0");
        IndicatorComparison fromZero = IndicatorComparison.of("orders", BigDecimal.TEN, BigDecimal.ZERO);
        assertThat(fromZero.percentAvailable()).isFalse();
        assertThat(fromZero.difference()).isEqualByComparingTo("10");
    }

    @Test
    void granularityKeepsLongPeriodsReadable() {
        assertThat(Granularity.forDays(62)).isEqualTo(Granularity.DAY);
        assertThat(Granularity.forDays(63)).isEqualTo(Granularity.WEEK);
        assertThat(Granularity.forDays(367)).isEqualTo(Granularity.MONTH);
        assertThat(Granularity.WEEK.bucketOf(LocalDate.of(2026, 6, 14))).isEqualTo(LocalDate.of(2026, 6, 8));
        assertThat(Granularity.MONTH.bucketOf(LocalDate.of(2026, 6, 14))).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(Granularity.MONTH.next(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    void emptyBucketsAreFilledSoTheChartHasNoGaps() {
        AnalyticsPeriod period = AnalyticsPeriod.of(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 15), TODAY, "period");
        List<SeriesPoint> points = AnalyzeSalesUseCaseAccess.withEmptyBuckets(period, Granularity.MONTH,
                List.of(new SeriesPoint(LocalDate.of(2026, 6, 1), new BigDecimal("10.00"), BigDecimal.ONE, 1)));
        assertThat(points).extracting(SeriesPoint::start)
                .containsExactly(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1));
        assertThat(points.get(0).orders()).isZero();
        assertThat(points.get(1).sales()).isEqualByComparingTo("10.00");
    }

    @Test
    void periodBoundariesCoverWholeDays() {
        AnalyticsPeriod period = AnalyticsPeriod.of(TODAY, TODAY, TODAY, "period");
        assertThat(period.days()).isEqualTo(1);
        assertThat(period.startInclusive()).isEqualTo(TODAY.atStartOfDay());
        assertThat(period.endExclusive()).isEqualTo(TODAY.plusDays(1).atStartOfDay());
        assertThat(AnalyticsPeriod.of(TODAY.minusDays(730), TODAY, TODAY, "period").days()).isEqualTo(731);
        assertThatThrownBy(() -> AnalyticsPeriod.of(TODAY.minusDays(731), TODAY, TODAY, "period"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void blankCategoryMeansNoFilter() {
        assertThat(new AnalyticsFilter(null, "  ").isEmpty()).isTrue();
        assertThat(new AnalyticsFilter(null, " Hogar ").category()).isEqualTo("Hogar");
        assertThat(AnalyticsFilter.none().isEmpty()).isTrue();
    }

    @Test
    void anExportThatCannotBeBuiltFailsWithAClearCodeSoItCanBeRetried() {
        SalesReport broken = new SalesReport(1L, "Tienda", AnalyticsFilter.none(), null, Granularity.DAY, null, null,
                List.of());
        assertThatThrownBy(() -> new PoiSalesReportExporter().export(broken))
                .isInstanceOf(SalesReportExporter.ExportFailedException.class);

        var controller = new SellerAnalyticsController(null, null, null, null);
        var request = new MockHttpServletRequest("GET", "/api/seller/analytics/export");
        var response = controller.exportFailed(new SalesReportExporter.ExportFailedException("x", null), request);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo("EXPORT_FAILED");
    }
}
