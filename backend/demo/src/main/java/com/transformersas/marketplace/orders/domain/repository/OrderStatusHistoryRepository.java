package com.transformersas.marketplace.orders.domain.repository;

import com.transformersas.marketplace.orders.domain.model.OrderStatusHistoryEntry;

import java.util.List;

/** Historial append-only de estados de un pedido. */
public interface OrderStatusHistoryRepository {

    void append(OrderStatusHistoryEntry entry);

    /** Entradas del pedido, de la más antigua a la más reciente. */
    List<OrderStatusHistoryEntry> findByOrderId(Long orderId);
}
