package com.transformersas.marketplace.analytics.domain.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** Agrupación de la serie temporal según la longitud del periodo, para que la gráfica siga siendo legible. */
public enum Granularity {
    DAY, WEEK, MONTH;

    public static Granularity forDays(long days) {
        if (days <= 62) {
            return DAY;
        }
        return days <= 366 ? WEEK : MONTH;
    }

    /** Primer día del grupo al que pertenece la fecha (las semanas empiezan el lunes). */
    public LocalDate bucketOf(LocalDate date) {
        return switch (this) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    public LocalDate next(LocalDate bucket) {
        return switch (this) {
            case DAY -> bucket.plusDays(1);
            case WEEK -> bucket.plusWeeks(1);
            case MONTH -> bucket.plusMonths(1);
        };
    }
}
