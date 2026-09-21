package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.RefreshOutcome;
import com.transformersas.marketplace.logistics.application.usecase.RefreshShipmentTrackingUseCase;
import com.transformersas.marketplace.orders.application.dto.OrderTracking;

import org.springframework.stereotype.Service;

/**
 * El comprador o la tienda pide actualizar el seguimiento: solo vuelve a consultar al servicio logístico (nadie puede
 * fijar estados a mano, A8). Primero comprueba que el pedido sea suyo. Si el servicio no responde, devuelve el último
 * seguimiento conocido con refresh = UNAVAILABLE (A7). NO es transaccional: la consulta externa va sin transacción.
 */
@Service
public class RefreshOrderTrackingUseCase {

    private final GetOrderTrackingUseCase getTracking;
    private final RefreshShipmentTrackingUseCase refreshShipment;

    public RefreshOrderTrackingUseCase(GetOrderTrackingUseCase getTracking,
                                       RefreshShipmentTrackingUseCase refreshShipment) {
        this.getTracking = getTracking;
        this.refreshShipment = refreshShipment;
    }

    public OrderTracking forBuyer(Long orderId, Long accountId) {
        getTracking.forBuyer(orderId, accountId);
        RefreshOutcome outcome = refreshShipment.execute(orderId);
        return withOutcome(getTracking.forBuyer(orderId, accountId), outcome);
    }

    public OrderTracking forSeller(Long orderId, Long storeId) {
        getTracking.forSeller(orderId, storeId);
        RefreshOutcome outcome = refreshShipment.execute(orderId);
        return withOutcome(getTracking.forSeller(orderId, storeId), outcome);
    }

    private static OrderTracking withOutcome(OrderTracking view, RefreshOutcome outcome) {
        return new OrderTracking(view.order(), view.shipment(), outcome);
    }
}
