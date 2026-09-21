package com.transformersas.marketplace.orders.infrastructure.web.response;

import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.orders.application.dto.SellerOrderDetail;
import com.transformersas.marketplace.orders.domain.model.DeliverySnapshot;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderIssue;
import com.transformersas.marketplace.orders.domain.model.OrderStatusHistoryEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Detalle del pedido para el vendedor (RF-112). */
public record SellerOrderDetailResponse(
        Long id,
        String status,
        String paymentStatus,
        BigDecimal total,
        LocalDateTime createdAt,
        String shippingMethod,
        Delivery delivery,
        List<Item> items,
        List<History> history,
        ShipmentInfo shipment,
        List<Issue> openIssues
) {
    public record Delivery(String recipientName, String street, String city, String department, String postalCode,
                           String phone) {
    }

    public record Item(Long productId, String name, int quantity, BigDecimal unitPrice, BigDecimal subtotal,
                       Integer currentStock, boolean inventoryConsistent) {
    }

    public record History(String fromStatus, String toStatus, String actorType, Long actorId, String reason,
                          String correlationId, LocalDateTime createdAt) {
    }

    public record ShipmentInfo(String shipmentId, String trackingCode, String status, LocalDateTime createdAt) {
    }

    public record Issue(Long id, String type, String description, String status, LocalDateTime createdAt) {
    }

    public static SellerOrderDetailResponse from(SellerOrderDetail detail) {
        Order order = detail.order();
        DeliverySnapshot d = order.delivery();
        return new SellerOrderDetailResponse(order.id(), order.status().name(), order.paymentStatus().name(),
                order.total(), order.createdAt(), order.shippingMethod(),
                new Delivery(d.recipientName(), d.street(), d.city(), d.department(), d.postalCode(), d.phone()),
                detail.items().stream().map(line -> new Item(line.item().productId(), line.item().productName(),
                        line.item().quantity(), line.item().unitPrice(), line.item().subtotal(), line.currentStock(),
                        line.inventoryConsistent())).toList(),
                detail.history().stream().map(SellerOrderDetailResponse::history).toList(),
                detail.shipment() == null ? null : shipment(detail.shipment()),
                detail.openIssues().stream().map(SellerOrderDetailResponse::issue).toList());
    }

    private static History history(OrderStatusHistoryEntry entry) {
        return new History(entry.fromStatus() == null ? null : entry.fromStatus().name(), entry.toStatus().name(),
                entry.actorType().name(), entry.actorId(), entry.reason(), entry.correlationId(), entry.createdAt());
    }

    private static ShipmentInfo shipment(Shipment shipment) {
        return new ShipmentInfo(shipment.providerShipmentId(), shipment.trackingCode(), shipment.status().name(),
                shipment.createdAt());
    }

    private static Issue issue(OrderIssue issue) {
        return new Issue(issue.id(), issue.type().name(), issue.description(), issue.status().name(),
                issue.createdAt());
    }
}
