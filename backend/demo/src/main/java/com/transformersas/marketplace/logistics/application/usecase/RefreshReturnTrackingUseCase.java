package com.transformersas.marketplace.logistics.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.RefreshOutcome;
import com.transformersas.marketplace.logistics.application.dto.ReturnTracking;
import com.transformersas.marketplace.logistics.application.dto.ReturnViewer;
import com.transformersas.marketplace.logistics.application.dto.TrackingPolicy;
import com.transformersas.marketplace.logistics.application.dto.UpdateResult;
import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.domain.model.TrackingSource;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;
import com.transformersas.marketplace.logistics.domain.repository.ReturnShipmentRepository;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Consulta al servicio logístico el retorno de una devolución y procesa lo que devuelva (paso 3, A6). Sirve al barrido
 * periódico y al botón «Actualizar» de quien puede ver la devolución. NO es transaccional: la llamada externa nunca va
 * dentro de una transacción. Si el servicio no responde se conserva el último estado conocido, sin inventar estados.
 */
@Service
public class RefreshReturnTrackingUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefreshReturnTrackingUseCase.class);

    private final LogisticsGateway gateway;
    private final ReturnShipmentRepository returns;
    private final ProcessReturnUpdateUseCase process;
    private final GetReturnTrackingUseCase getTracking;
    private final TrackingPolicy policy;

    public RefreshReturnTrackingUseCase(LogisticsGateway gateway, ReturnShipmentRepository returns,
                                        ProcessReturnUpdateUseCase process, GetReturnTrackingUseCase getTracking,
                                        TrackingPolicy policy) {
        this.gateway = gateway;
        this.returns = returns;
        this.process = process;
        this.getTracking = getTracking;
        this.policy = policy;
    }

    /** Consulta pedida por el comprador o la tienda: valida que pueda verla y devuelve el seguimiento resultante. */
    public ReturnTracking execute(Long returnId, ReturnViewer viewer) {
        ReturnShipment shipment = returns.findByReturnId(returnId).filter(viewer::canView).orElseThrow(() ->
                BusinessException.notFound("RETURN_NOT_FOUND", "La devolución no existe"));
        RefreshOutcome outcome = refresh(shipment, true);
        return getTracking.execute(returnId, viewer).withRefresh(outcome);
    }

    /** Consulta una devolución concreta; throttled = respetar el intervalo mínimo desde la última consulta. */
    public RefreshOutcome refresh(ReturnShipment shipment, boolean throttled) {
        TrackingState state = returns.findState(shipment.id()).orElse(null);
        if (state == null || !state.active()) {
            return RefreshOutcome.NOT_TRACKED;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = state.lastPolledAt();
        if (throttled && last != null && last.plus(policy.minRefreshInterval()).isAfter(now)) {
            return RefreshOutcome.THROTTLED;
        }

        List<ReturnTrackingUpdate> updates;
        try {
            updates = gateway.fetchReturnUpdates(shipment.providerReturnId());
        } catch (LogisticsUnavailableException | LogisticsRejectedException failure) {
            returns.markPolled(shipment.id(), false, now);
            log.warn("Consulta logística de devolución fallida returnId={} tipo={}; se conserva el último estado",
                    shipment.returnId(), failure.getClass().getSimpleName());
            return RefreshOutcome.UNAVAILABLE;
        }
        returns.markPolled(shipment.id(), true, now);

        boolean changed = false;
        for (ReturnTrackingUpdate update : updates.stream()
                .sorted(Comparator.comparing(ReturnTrackingUpdate::occurredAt)).toList()) {
            try {
                changed |= process.execute(update, TrackingSource.POLLING) != UpdateResult.DUPLICATE;
            } catch (BusinessException rejected) {
                log.warn("Actualización consultada descartada returnId={} código={}", shipment.returnId(),
                        rejected.code());
            }
        }
        return changed ? RefreshOutcome.UPDATED : RefreshOutcome.NO_CHANGES;
    }
}
