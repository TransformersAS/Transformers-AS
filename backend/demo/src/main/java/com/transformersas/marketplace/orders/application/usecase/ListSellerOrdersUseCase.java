package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.orders.application.dto.ListOrdersQuery;
import com.transformersas.marketplace.orders.domain.model.OrderSearchCriteria;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.model.OrderSummary;
import com.transformersas.marketplace.orders.domain.model.PageResult;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/** Lista los pedidos de la tienda del vendedor (RF-111). Por defecto muestra los que requieren preparación. */
@Service
public class ListSellerOrdersUseCase {

    /** Estados que permiten preparación (RF-111). */
    private static final Set<OrderStatus> DEFAULT_STATUSES = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.IN_PREPARATION);

    private final OrderRepository orders;

    public ListSellerOrdersUseCase(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public PageResult<OrderSummary> execute(ListOrdersQuery query) {
        if (query.from() != null && query.to() != null && query.from().isAfter(query.to())) {
            throw BusinessException.invalid("INVALID_DATE_RANGE", "La fecha inicial no puede ser posterior a la final");
        }
        Set<OrderStatus> statuses = query.statuses() == null || query.statuses().isEmpty()
                ? DEFAULT_STATUSES : EnumSet.copyOf(query.statuses());
        LocalDateTime from = query.from() == null ? null : query.from().atStartOfDay();
        LocalDateTime before = query.to() == null ? null : query.to().plusDays(1).atStartOfDay();

        return orders.search(new OrderSearchCriteria(query.storeId(), statuses, from, before, query.orderId(),
                query.page(), query.size()));
    }
}
