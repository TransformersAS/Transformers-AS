package com.transformersas.marketplace.analytics.application.usecase;

import com.transformersas.marketplace.analytics.domain.model.AnalyticsPeriod;
import com.transformersas.marketplace.analytics.domain.model.Granularity;
import com.transformersas.marketplace.analytics.domain.model.SalesBreakdown.SeriesPoint;

import java.util.List;

/** Acceso de prueba al relleno de la serie, que es interno del caso de uso. */
public final class AnalyzeSalesUseCaseAccess {

    private AnalyzeSalesUseCaseAccess() {
    }

    public static List<SeriesPoint> withEmptyBuckets(AnalyticsPeriod period, Granularity granularity,
                                                     List<SeriesPoint> points) {
        return AnalyzeSalesUseCase.withEmptyBuckets(period, granularity, points);
    }
}
