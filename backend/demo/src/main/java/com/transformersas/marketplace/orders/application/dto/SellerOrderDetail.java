package com.transformersas.marketplace.orders.application.dto;

import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderIssue;
import com.transformersas.marketplace.orders.domain.model.OrderItem;
import com.transformersas.marketplace.orders.domain.model.OrderStatusHistoryEntry;

import java.util.List;

/** Vista completa de un pedido para el vendedor (RF-112). Los datos de entrega salen del snapshot de la compra. */
public record SellerOrderDetail(
        Order order,
        List<Line> items,
        List<OrderStatusHistoryEntry> history,
        Shipment shipment,
        List<OrderIssue> openIssues
) {
    /** currentStock es null si el producto ya no existe; inventoryConsistent indica si se puede preparar. */
    public record Line(OrderItem item, Integer currentStock, boolean inventoryConsistent) {
    }
}
