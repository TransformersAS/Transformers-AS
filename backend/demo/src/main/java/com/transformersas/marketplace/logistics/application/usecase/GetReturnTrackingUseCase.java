package com.transformersas.marketplace.logistics.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.ReturnTracking;
import com.transformersas.marketplace.logistics.application.dto.ReturnViewer;
import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;
import com.transformersas.marketplace.logistics.domain.repository.ReturnShipmentRepository;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seguimiento logístico de una devolución para su comprador o su tienda (RF-051, RF-110, A7). La devolución de otra
 * cuenta o tienda es indistinguible de una inexistente (404): 0 accesos a datos de otros (RNF-010).
 */
@Service
public class GetReturnTrackingUseCase {

    private final ReturnShipmentRepository returns;

    public GetReturnTrackingUseCase(ReturnShipmentRepository returns) {
        this.returns = returns;
    }

    @Transactional(readOnly = true)
    public ReturnTracking execute(Long returnId, ReturnViewer viewer) {
        ReturnShipment shipment = returns.findByReturnId(returnId).filter(viewer::canView).orElseThrow(() ->
                BusinessException.notFound("RETURN_NOT_FOUND", "La devolución no existe"));
        TrackingState state = returns.findState(shipment.id()).orElseThrow();
        return new ReturnTracking(shipment, returns.findEvents(shipment.id()), state.active(), state.lastPollFailed(),
                state.lastPolledAt(), null);
    }
}
