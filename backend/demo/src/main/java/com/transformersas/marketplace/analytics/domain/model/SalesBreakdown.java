package com.transformersas.marketplace.analytics.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Desgloses del periodo para tablas y gráficas. Los montos están en pesos con dos decimales. */
public final class SalesBreakdown {

    private SalesBreakdown() {
    }

    /**
     * Ventas de un producto. profit = sales − refunds − costs; unitsWithoutCost &gt; 0 indica que su costo es parcial.
     */
    public record ProductSales(Long productId, String name, String category, long units, BigDecimal sales,
                               BigDecimal refunds, BigDecimal costs, BigDecimal profit, long unitsWithoutCost) {
    }

    public record CategorySales(String category, long units, BigDecimal sales) {
    }

    /** Un punto de la serie: el grupo que empieza en {@code start}. */
    public record SeriesPoint(LocalDate start, BigDecimal sales, BigDecimal costs, long orders) {
    }
}
