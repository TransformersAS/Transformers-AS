package com.transformersas.marketplace.orders.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/** Filtros del comprador. Las fechas se interpretan como días de creación, sin conversión de zona. */
public record BuyerOrderFilters(LocalDate from, LocalDate to, OrderStatus status) {
    public BuyerOrderFilters {
        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.invalid("INVALID_DATE_RANGE", "La fecha inicial no puede ser posterior a la final");
        }
    }

    public static BuyerOrderFilters parse(String from, String to, String status) {
        LocalDate start = date(from, "from");
        LocalDate end = date(to, "to");
        OrderStatus state = null;
        if (status != null) {
            try {
                state = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException exception) {
                throw BusinessException.invalid("INVALID_ORDER_STATUS", "El estado del pedido no es válido");
            }
        }
        return new BuyerOrderFilters(start, end, state);
    }

    private static LocalDate date(String value, String parameter) {
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("Formato", value, 0);
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1000) throw new DateTimeParseException("Año", value, 0);
            return date;
        } catch (DateTimeParseException exception) {
            throw BusinessException.invalid("INVALID_ORDER_DATE",
                    "El parámetro " + parameter + " debe ser una fecha válida AAAA-MM-DD, entre los años 1000 y 9999");
        }
    }

    public LocalDateTime createdFrom() { return from == null ? null : from.atStartOfDay(); }

    /** DATETIME(6): incluye el último microsegundo sin desbordar el año 9999. */
    public LocalDateTime createdThrough() {
        return to == null ? null : to.atTime(LocalTime.of(23, 59, 59, 999_999_000));
    }
}
