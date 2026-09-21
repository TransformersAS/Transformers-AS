package com.transformersas.marketplace.logistics.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.UpdateResult;
import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.logistics.domain.model.TrackingConflictException;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvent;
import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.logistics.domain.model.TrackingSource;
import com.transformersas.marketplace.logistics.domain.model.TrackingUpdate;
import com.transformersas.marketplace.logistics.domain.repository.OrderTrackingPort;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentRepository;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentTrackingRepository;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.web.CorrelationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Procesa una actualización logística de un pedido, llegue por webhook o por consulta (RF-116, RF-118, RF-119, A1 a
 * A6, RNF-043, RNF-046). Es el ÚNICO punto por el que cambia el estado de transporte de un pedido.
 *
 * <p>Pasos: identifica el envío por su referencia logística y verifica que la guía corresponda (pasos 2 y 3). Después,
 * en UNA transacción: guarda la actualización (la UNIQUE por envío y evento la vuelve idempotente: un evento repetido
 * no tiene ningún efecto) y le pide al módulo de pedidos que la aplique con compare-and-set, junto con historial,
 * auditoría y notificaciones. Si el pedido cambió entretanto se revierte todo y se reintenta leyendo el estado nuevo.
 * NO es transaccional por sí mismo ni llama a ningún servicio externo.
 */
@Service
public class ProcessShipmentUpdateUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessShipmentUpdateUseCase.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ShipmentRepository shipments;
    private final ShipmentTrackingRepository tracking;
    private final OrderTrackingPort orderTracking;
    private final AuditRecorder audit;
    private final TransactionTemplate transaction;

    public ProcessShipmentUpdateUseCase(ShipmentRepository shipments, ShipmentTrackingRepository tracking,
                                        OrderTrackingPort orderTracking, AuditRecorder audit,
                                        TransactionTemplate transaction) {
        this.shipments = shipments;
        this.tracking = tracking;
        this.orderTracking = orderTracking;
        this.audit = audit;
        this.transaction = transaction;
    }

    public UpdateResult execute(TrackingUpdate update, TrackingSource source) {
        Shipment shipment = shipments.findByProviderShipmentId(update.shipmentId()).orElseThrow(() ->
                BusinessException.notFound("SHIPMENT_NOT_FOUND", "No existe un envío con esa referencia logística"));
        if (!shipment.trackingCode().equals(update.trackingCode())) {
            log.warn("Actualización con guía distinta orderId={} eventId={}", shipment.orderId(), update.eventId());
            audit.record(ActorType.LOGISTICS, null, "TRACKING_UPDATE_REJECTED", "ORDER", shipment.orderId(),
                    AuditOutcome.FAILURE, Map.of("eventId", update.eventId(), "reason", "TRACKING_CODE_MISMATCH"));
            throw BusinessException.conflict("SHIPMENT_MISMATCH", "La actualización no corresponde a ese envío");
        }

        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> apply(shipment, update, source));
            } catch (TrackingConflictException | ConcurrencyFailureException conflict) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Conflicto persistente al aplicar la actualización orderId={} eventId={}",
                            shipment.orderId(), update.eventId());
                    throw BusinessException.conflict("ORDER_STATE_CONFLICT",
                            "El pedido cambió de estado mientras se procesaba la actualización; se puede reenviar");
                }
            }
        }
    }

    private UpdateResult apply(Shipment shipment, TrackingUpdate update, TrackingSource source) {
        // Primer paso de la transacción: las actualizaciones del mismo envío se procesan de una en una.
        tracking.lock(shipment.id());
        var insertion = tracking.insertEventIfAbsent(new TrackingEvent(null, shipment.id(), shipment.orderId(),
                update.eventId(), update.type(), LocalDateTime.ofInstant(update.occurredAt(), ZoneId.systemDefault()),
                LocalDateTime.now(), source, TrackingOutcome.RECORDED, update.description(), update.location(),
                update.evidence(), CorrelationContext.current()));
        if (!insertion.created()) {
            log.info("Actualización repetida omitida orderId={} eventId={}", shipment.orderId(), update.eventId());
            return UpdateResult.DUPLICATE;
        }

        TrackingOutcome outcome = orderTracking.apply(shipment.orderId(), update);
        if (outcome != TrackingOutcome.RECORDED) {
            tracking.updateEventOutcome(insertion.event().id(), outcome);
        }
        if (outcome == TrackingOutcome.APPLIED && update.type().closesTracking()) {
            tracking.closeTracking(shipment.id());
        }
        log.info("Actualización logística orderId={} tipo={} resultado={} origen={}", shipment.orderId(),
                update.type(), outcome, source);
        return UpdateResult.valueOf(outcome.name());
    }
}
