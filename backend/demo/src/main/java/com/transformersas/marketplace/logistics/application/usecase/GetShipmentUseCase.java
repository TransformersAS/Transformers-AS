package com.transformersas.marketplace.logistics.application.usecase;

import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Consulta la referencia logística de un pedido (RF-115). CU-24 podrá ampliarla con el estado de transporte. */
@Service
public class GetShipmentUseCase {

    private final ShipmentRepository shipments;

    public GetShipmentUseCase(ShipmentRepository shipments) {
        this.shipments = shipments;
    }

    @Transactional(readOnly = true)
    public Optional<Shipment> execute(Long orderId) {
        return shipments.findByOrderId(orderId);
    }
}
