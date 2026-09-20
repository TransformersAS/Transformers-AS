package com.transformersas.marketplace.logistics.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.ShipmentTracking;
import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentRepository;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentTrackingRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Seguimiento de un pedido para quien ya comprobó que puede verlo (RF-117): línea de tiempo en orden cronológico y
 * estado de la consulta. Devuelve vacío si el pedido todavía no tiene envío.
 */
@Service
public class GetShipmentTrackingUseCase {

    private final ShipmentRepository shipments;
    private final ShipmentTrackingRepository tracking;

    public GetShipmentTrackingUseCase(ShipmentRepository shipments, ShipmentTrackingRepository tracking) {
        this.shipments = shipments;
        this.tracking = tracking;
    }

    @Transactional(readOnly = true)
    public Optional<ShipmentTracking> execute(Long orderId) {
        Optional<Shipment> shipment = shipments.findByOrderId(orderId);
        if (shipment.isEmpty()) {
            return Optional.empty();
        }
        TrackingState state = tracking.findState(shipment.get().id()).orElseThrow();
        return Optional.of(new ShipmentTracking(shipment.get(), tracking.findEvents(shipment.get().id()),
                state.active(), state.lastPollFailed(), state.lastPolledAt()));
    }
}
