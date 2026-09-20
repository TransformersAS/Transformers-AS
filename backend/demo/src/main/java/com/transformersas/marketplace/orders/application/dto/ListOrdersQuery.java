package com.transformersas.marketplace.orders.application.dto;

import com.transformersas.marketplace.orders.domain.model.OrderStatus;

import java.time.LocalDate;
import java.util.List;

/** Filtros del listado de pedidos del vendedor. from y to son fechas (inclusivas) de created_at. */
public record ListOrdersQuery(
        Long storeId,
        List<OrderStatus> statuses,
        LocalDate from,
        LocalDate to,
        Long orderId,
        int page,
        int size
) {
}
