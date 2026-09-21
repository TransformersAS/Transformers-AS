package com.transformersas.marketplace.orders.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.CreateShipmentCommand;
import com.transformersas.marketplace.logistics.application.dto.ShipmentResult;
import com.transformersas.marketplace.logistics.application.usecase.CreateShipmentUseCase;
import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.orders.application.dto.ShipmentOutcome;
import com.transformersas.marketplace.orders.domain.model.DeliverySnapshot;
import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.shared.audit.ActorType;

import org.springframework.stereotype.Component;

/**
 * Solicita a logística el envío de un pedido Listo para despacho usando el snapshot de la compra (pasos 12-13).
 * No es transaccional: la llamada externa nunca ocurre dentro de una transacción de BD. Traduce los fallos del
 * proveedor a un resultado, no a una excepción, para que cada caso de uso decida cómo responder.
 */
@Component
class ShipmentRequester {

    private final CreateShipmentUseCase createShipment;

    ShipmentRequester(CreateShipmentUseCase createShipment) {
        this.createShipment = createShipment;
    }

    ShipmentOutcome request(Order order, Long actorId) {
        DeliverySnapshot delivery = order.delivery();
        var request = new ShipmentRequest(order.id(), order.shippingMethod(),
                new ShipmentRequest.Recipient(delivery.recipientName(), delivery.street(), delivery.city(),
                        delivery.department(), delivery.postalCode(), delivery.phone()),
                order.items().stream().map(item -> new ShipmentRequest.Item(item.productName(), item.quantity())).toList());
        try {
            ShipmentResult result = createShipment.execute(new CreateShipmentCommand(request, ActorType.SELLER, actorId));
            return ShipmentOutcome.created(result.shipment().providerShipmentId(), result.shipment().trackingCode(),
                    result.created());
        } catch (LogisticsRejectedException rejected) {
            return ShipmentOutcome.failed("El servicio logístico rechazó la solicitud de envío");
        } catch (LogisticsUnavailableException unavailable) {
            return ShipmentOutcome.failed("El servicio logístico no está disponible; puedes reintentar la solicitud");
        }
    }
}
