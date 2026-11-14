package com.transformersas.marketplace.analytics.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Periodo de días completos, ambos extremos incluidos. Se valida al crearlo (A2): las dos fechas son obligatorias, la
 * inicial no puede ser posterior a la final ni al día de hoy, y el rango no supera {@link #MAX_DAYS} días.
 */
public record AnalyticsPeriod(LocalDate from, LocalDate to) {

    public static final int MAX_DAYS = 731;

    /** Valida el periodo; {@code label} distingue el principal del de comparación en el error. */
    public static AnalyticsPeriod of(LocalDate from, LocalDate to, LocalDate today, String label) {
        String code = "comparison".equals(label) ? "INVALID_COMPARISON_PERIOD" : "INVALID_PERIOD";
        if (from == null || to == null) {
            throw invalid(code, "Indica la fecha inicial y la final del periodo", from == null ? "from" : "to");
        }
        if (from.isAfter(to)) {
            throw invalid(code, "La fecha inicial no puede ser posterior a la final", "from");
        }
        if (from.isAfter(today)) {
            throw invalid(code, "El periodo no puede empezar después de hoy", "from");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw invalid(code, "El periodo admite como máximo " + MAX_DAYS + " días", "to");
        }
        return new AnalyticsPeriod(from, to);
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    public LocalDateTime startInclusive() {
        return from.atStartOfDay();
    }

    public LocalDateTime endExclusive() {
        return to.plusDays(1).atStartOfDay();
    }

    private static BusinessException invalid(String code, String message, String field) {
        return new BusinessException(BusinessException.Kind.INVALID, code, message, Map.of("field", field));
    }
}
