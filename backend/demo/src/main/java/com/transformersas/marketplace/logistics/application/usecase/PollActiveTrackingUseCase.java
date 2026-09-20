package com.transformersas.marketplace.logistics.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.TrackingPolicy;
import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;
import com.transformersas.marketplace.logistics.domain.repository.ReturnShipmentRepository;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentRepository;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentTrackingRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Barrido periódico (A7): vuelve a consultar los envíos y devoluciones activos que llevan más de la mitad de
 * pollInterval sin consultarse, los más atrasados primero y hasta batchSize de cada tipo. Un fallo con un envío no detiene el resto ni
 * al barrido siguiente. NO es transaccional: cada consulta y cada actualización tienen su propia transacción.
 */
@Service
public class PollActiveTrackingUseCase {

    private static final Logger log = LoggerFactory.getLogger(PollActiveTrackingUseCase.class);

    private final ShipmentTrackingRepository shipmentTracking;
    private final ShipmentRepository shipments;
    private final ReturnShipmentRepository returns;
    private final RefreshShipmentTrackingUseCase refreshShipment;
    private final RefreshReturnTrackingUseCase refreshReturn;
    private final TrackingPolicy policy;

    public PollActiveTrackingUseCase(ShipmentTrackingRepository shipmentTracking, ShipmentRepository shipments,
                                     ReturnShipmentRepository returns, RefreshShipmentTrackingUseCase refreshShipment,
                                     RefreshReturnTrackingUseCase refreshReturn, TrackingPolicy policy) {
        this.shipmentTracking = shipmentTracking;
        this.shipments = shipments;
        this.returns = returns;
        this.refreshShipment = refreshShipment;
        this.refreshReturn = refreshReturn;
        this.policy = policy;
    }

    /** Devuelve cuántos envíos y devoluciones consultó. */
    public int execute() {
        // Vence lo que lleva más de la mitad del intervalo: si vencieran solo los de más de un intervalo completo, con
        // la variación normal entre barridos se saltaría uno de cada dos y el seguimiento se actualizaría cada doble.
        LocalDateTime dueBefore = LocalDateTime.now().minus(policy.pollInterval().dividedBy(2));
        int polled = 0;
        for (TrackingState state : shipmentTracking.findDueForPolling(dueBefore, policy.batchSize())) {
            try {
                var shipment = shipments.findById(state.subjectId());
                if (shipment.isPresent()) {
                    refreshShipment.refresh(shipment.get(), false);
                    polled++;
                }
            } catch (RuntimeException failure) {
                log.error("Fallo consultando el envío shipmentId={}", state.subjectId(), failure);
            }
        }
        for (ReturnShipment shipment : returns.findDueForPolling(dueBefore, policy.batchSize())) {
            try {
                refreshReturn.refresh(shipment, false);
                polled++;
            } catch (RuntimeException failure) {
                log.error("Fallo consultando la devolución returnId={}", shipment.returnId(), failure);
            }
        }
        return polled;
    }
}
