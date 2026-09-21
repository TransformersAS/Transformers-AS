package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.inventory.application.usecase.GetStockLevelsUseCase;
import com.transformersas.marketplace.logistics.application.usecase.GetShipmentUseCase;
import com.transformersas.marketplace.orders.application.dto.SellerOrderDetail;
import com.transformersas.marketplace.orders.domain.model.InventoryConsistency;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderItem;
import com.transformersas.marketplace.orders.domain.repository.OrderIssueRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.orders.domain.repository.OrderStatusHistoryRepository;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Detalle de un pedido de la tienda (RF-112). Un pedido de otra tienda es indistinguible de uno inexistente (404).
 * Los datos de entrega salen del snapshot guardado en la compra, nunca de la dirección vigente del comprador.
 */
@Service
public class GetSellerOrderDetailUseCase {

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final OrderIssueRepository issues;
    private final GetStockLevelsUseCase stock;
    private final GetShipmentUseCase shipments;

    public GetSellerOrderDetailUseCase(OrderRepository orders, OrderStatusHistoryRepository history,
                                       OrderIssueRepository issues, GetStockLevelsUseCase stock,
                                       GetShipmentUseCase shipments) {
        this.orders = orders;
        this.history = history;
        this.issues = issues;
        this.stock = stock;
        this.shipments = shipments;
    }

    @Transactional(readOnly = true)
    public SellerOrderDetail execute(Long orderId, Long storeId) {
        Order order = orders.findByIdAndStoreId(orderId, storeId)
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "El pedido no existe"));

        Map<Long, Integer> levels = stock.execute(order.items().stream().map(OrderItem::productId).distinct().toList());
        List<SellerOrderDetail.Line> lines = order.items().stream().map(item -> {
            Integer current = levels.get(item.productId());
            return new SellerOrderDetail.Line(item, current, InventoryConsistency.isConsistent(current));
        }).toList();

        return new SellerOrderDetail(order, lines, history.findByOrderId(orderId),
                shipments.execute(orderId).orElse(null), issues.findOpenByOrderId(orderId));
    }
}
