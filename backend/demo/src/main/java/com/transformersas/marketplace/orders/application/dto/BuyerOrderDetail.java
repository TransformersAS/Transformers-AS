package com.transformersas.marketplace.orders.application.dto;

import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.model.OrderStatusHistoryEntry;
import java.time.LocalDateTime;
import java.util.List;

/** Proyección de lectura del comprador: no expone actores, motivos ni correlaciones internas. */
public record BuyerOrderDetail(Order order, List<History> history, List<BuyerRefund> refunds) {
    public record History(OrderStatus fromStatus, OrderStatus toStatus, LocalDateTime createdAt, boolean legacy) {
        public static History from(OrderStatusHistoryEntry entry) {
            boolean legacy = "migration-V14".equals(entry.correlationId());
            return new History(entry.fromStatus(), entry.toStatus(), legacy ? null : entry.createdAt(), legacy);
        }
    }
}
