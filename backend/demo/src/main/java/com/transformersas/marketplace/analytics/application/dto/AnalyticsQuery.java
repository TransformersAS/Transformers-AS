package com.transformersas.marketplace.analytics.application.dto;

import java.time.LocalDate;

/**
 * Lo que pide el vendedor: el periodo principal, opcionalmente un periodo de comparación (las dos fechas o ninguna) y
 * los filtros. La tienda no viaja aquí: sale de la sesión.
 */
public record AnalyticsQuery(LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo, Long productId,
                             String category) {

    public boolean wantsComparison() {
        return compareFrom != null || compareTo != null;
    }
}
