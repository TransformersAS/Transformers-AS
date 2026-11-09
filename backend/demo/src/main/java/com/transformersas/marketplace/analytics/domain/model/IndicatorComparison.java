package com.transformersas.marketplace.analytics.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Un indicador en los dos periodos (RF-125): difference = actual − comparación; percentChange es esa diferencia sobre
 * el valor del periodo de comparación, que es la base. Si la base es cero el porcentaje no se puede calcular y queda
 * nulo (A5); los valores absolutos se muestran igual.
 */
public record IndicatorComparison(String indicator, BigDecimal current, BigDecimal previous, BigDecimal difference,
                                  BigDecimal percentChange) {

    public static IndicatorComparison of(String indicator, BigDecimal current, BigDecimal previous) {
        BigDecimal difference = current.subtract(previous);
        BigDecimal percent = previous.signum() == 0 ? null
                : difference.multiply(BigDecimal.valueOf(100)).divide(previous.abs(), 1, RoundingMode.HALF_UP);
        return new IndicatorComparison(indicator, current, previous, difference, percent);
    }

    public boolean percentAvailable() {
        return percentChange != null;
    }

    /** Los indicadores comparables, siempre en el mismo orden. */
    public static List<IndicatorComparison> between(SalesIndicators current, SalesIndicators previous) {
        return List.of(
                of("sales", current.sales(), previous.sales()),
                of("refunds", current.refunds(), previous.refunds()),
                of("netRevenue", current.netRevenue(), previous.netRevenue()),
                of("costs", current.costs(), previous.costs()),
                of("profit", current.profit(), previous.profit()),
                of("orders", BigDecimal.valueOf(current.orders()), BigDecimal.valueOf(previous.orders())),
                of("units", BigDecimal.valueOf(current.units()), BigDecimal.valueOf(previous.units())),
                of("averageTicket", current.averageTicket(), previous.averageTicket()));
    }
}
