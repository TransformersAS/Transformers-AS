package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.logistics.application.usecase.GetShipmentTrackingUseCase;
import com.transformersas.marketplace.orders.application.dto.OrderTracking;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seguimiento logístico de un pedido para su comprador o su tienda (RF-117, postcondición 4). Un pedido ajeno es
 * indistinguible de uno inexistente (404): 0 accesos a datos de otras cuentas o tiendas (RNF-010).
 */
@Service
public class GetOrderTrackingUseCase {

    private final OrderRepository orders;
    private final GetShipmentTrackingUseCase shipmentTracking;

    public GetOrderTrackingUseCase(OrderRepository orders, GetShipmentTrackingUseCase shipmentTracking) {
        this.orders = orders;
        this.shipmentTracking = shipmentTracking;
    }

    @Transactional(readOnly = true)
    public OrderTracking forBuyer(Long orderId, Long accountId) {
        return view(orders.findByIdAndAccountId(orderId, accountId).orElseThrow(GetOrderTrackingUseCase::notFound));
    }

    @Transactional(readOnly = true)
    public OrderTracking forSeller(Long orderId, Long storeId) {
        return view(orders.findByIdAndStoreId(orderId, storeId).orElseThrow(GetOrderTrackingUseCase::notFound));
    }

    private OrderTracking view(Order order) {
        return new OrderTracking(order, shipmentTracking.execute(order.id()).orElse(null), null);
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("ORDER_NOT_FOUND", "El pedido no existe");
    }
}
