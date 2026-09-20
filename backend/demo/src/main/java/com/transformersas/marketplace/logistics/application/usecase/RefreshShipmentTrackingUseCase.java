package com.transformersas.marketplace.logistics.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.RefreshOutcome;
import com.transformersas.marketplace.logistics.application.dto.TrackingPolicy;
import com.transformersas.marketplace.logistics.application.dto.UpdateResult;
import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.logistics.domain.model.TrackingSource;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentRepository;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentTrackingRepository;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Consulta al servicio logístico el seguimiento de un pedido y procesa lo que devuelva (paso 1, A7). Sirve al barrido
 * periódico y al botón «Actualizar» del comprador o vendedor. NO es transaccional: la llamada externa nunca va dentro
 * de una transacción; cada actualización se procesa en la suya. Si el servicio no responde se conserva el último
 * seguimiento conocido, sin inventar estados, y se vuelve a consultar más tarde.
 */
@Service
public class RefreshShipmentTrackingUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefreshShipmentTrackingUseCase.class);

    private final LogisticsGateway gateway;
    private final ShipmentRepository shipments;
    private final ShipmentTrackingRepository tracking;
    private final ProcessShipmentUpdateUseCase process;
    private final TrackingPolicy policy;

    public RefreshShipmentTrackingUseCase(LogisticsGateway gateway, ShipmentRepository shipments,
                                          ShipmentTrackingRepository tracking, ProcessShipmentUpdateUseCase process,
                                          TrackingPolicy policy) {
        this.gateway = gateway;
        this.shipments = shipments;
        this.tracking = tracking;
        this.process = process;
        this.policy = policy;
    }

    /** Consulta pedida por un usuario: respeta el intervalo mínimo para no saturar al proveedor. */
    public RefreshOutcome execute(Long orderId) {
        Optional<Shipment> shipment = shipments.findByOrderId(orderId);
        return shipment.isPresent() ? refresh(shipment.get(), true) : RefreshOutcome.NOT_TRACKED;
    }

    /** Consulta un envío concreto; throttled = respetar el intervalo mínimo desde la última consulta. */
    public RefreshOutcome refresh(Shipment shipment, boolean throttled) {
        Optional<TrackingState> state = tracking.findState(shipment.id());
        if (state.isEmpty() || !state.get().active()) {
            return RefreshOutcome.NOT_TRACKED;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = state.get().lastPolledAt();
        if (throttled && last != null && last.plus(policy.minRefreshInterval()).isAfter(now)) {
            return RefreshOutcome.THROTTLED;
        }

        List<TrackingUpdate> updates;
        try {
            updates = gateway.fetchShipmentUpdates(shipment.providerShipmentId());
        } catch (LogisticsUnavailableException | LogisticsRejectedException failure) {
            tracking.markPolled(shipment.id(), false, now);
            log.warn("Consulta logística fallida orderId={} tipo={}; se conserva el último seguimiento",
                    shipment.orderId(), failure.getClass().getSimpleName());
            return RefreshOutcome.UNAVAILABLE;
        }
        tracking.markPolled(shipment.id(), true, now);

        boolean changed = false;
        for (TrackingUpdate update : updates.stream().sorted(Comparator.comparing(TrackingUpdate::occurredAt)).toList()) {
            try {
                changed |= process.execute(update, TrackingSource.POLLING) != UpdateResult.DUPLICATE;
            } catch (BusinessException rejected) {
                // Una actualización que no corresponde a este envío no impide procesar las demás.
                log.warn("Actualización consultada descartada orderId={} código={}", shipment.orderId(),
                        rejected.code());
            }
        }
        return changed ? RefreshOutcome.UPDATED : RefreshOutcome.NO_CHANGES;
    }
}
