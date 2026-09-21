package com.transformersas.marketplace.returns.infrastructure.integration;

import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ReturnReceipt;
import com.transformersas.marketplace.logistics.domain.model.ReturnRequestData;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;
import com.transformersas.marketplace.returns.domain.port.ReturnLogistics;
import org.springframework.stereotype.Component;

import java.util.List;

/** Habla con el servicio logístico por su gateway (real o simulado) y traduce sus errores a los del puerto. */
@Component
class GatewayReturnLogistics implements ReturnLogistics {
    private final LogisticsGateway gateway;

    GatewayReturnLogistics(LogisticsGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public List<Method> methods(Long orderId, Long storeId) {
        try {
            return gateway.fetchReturnMethods(orderId, storeId).stream()
                    .map(method -> new Method(method.code(), method.label())).toList();
        } catch (LogisticsUnavailableException unavailable) {
            throw new UnavailableException(unavailable.getMessage(), unavailable);
        } catch (LogisticsRejectedException rejected) {
            throw new RejectedException(rejected.getMessage(), rejected);
        }
    }

    @Override
    public Receipt createReturn(Shipment shipment) {
        var pickup = shipment.pickup();
        ReturnRequestData data = new ReturnRequestData(shipment.returnId(), shipment.orderId(), shipment.storeId(),
                shipment.methodCode(), new ShipmentRequest.Recipient(pickup.name(), pickup.street(), pickup.city(),
                pickup.department(), pickup.postalCode(), pickup.phone()),
                List.of(new ShipmentRequest.Item(shipment.itemName(), shipment.quantity())));
        try {
            ReturnReceipt receipt = gateway.createReturn(data, shipment.idempotencyKey());
            return new Receipt(receipt.providerReturnId(), receipt.trackingCode());
        } catch (LogisticsUnavailableException unavailable) {
            throw new UnavailableException(unavailable.getMessage(), unavailable);
        } catch (LogisticsRejectedException rejected) {
            throw new RejectedException(rejected.getMessage(), rejected);
        }
    }
}
