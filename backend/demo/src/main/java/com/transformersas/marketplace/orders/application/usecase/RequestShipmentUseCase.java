package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.orders.application.dto.OrderActionCommand;
import com.transformersas.marketplace.orders.application.dto.ShipmentOutcome;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.repository.OrderRepository;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.stereotype.Service;

/**
 * Reintento manual de la creación del envío (A5, A6, RF-114). Solo para pedidos Listo para despacho. Si ya existe un
 * envío se devuelve su referencia sin llamar al proveedor (created = false); si no, se crea. Si el proveedor falla
 * responde 502 y el pedido sigue Listo para despacho. No es transaccional: la llamada externa va sin transacción.
 */
@Service
public class RequestShipmentUseCase {

    private final OrderRepository orders;
    private final ShipmentRequester shipmentRequester;

    public RequestShipmentUseCase(OrderRepository orders, ShipmentRequester shipmentRequester) {
        this.orders = orders;
        this.shipmentRequester = shipmentRequester;
    }

    public ShipmentOutcome execute(OrderActionCommand command) {
        Order order = orders.findByIdAndStoreId(command.orderId(), command.storeId())
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "El pedido no existe"));
        if (order.status() != OrderStatus.READY_FOR_DISPATCH) {
            throw BusinessException.conflict("ORDER_NOT_READY_FOR_DISPATCH",
                    "Solo se solicita el envío de pedidos Listos para despacho");
        }

        ShipmentOutcome outcome = shipmentRequester.request(order, command.actorId());
        if (outcome.status() == ShipmentOutcome.Status.FAILED) {
            throw BusinessException.badGateway("SHIPMENT_PROVIDER_FAILED", outcome.message());
        }
        return outcome;
    }
}
