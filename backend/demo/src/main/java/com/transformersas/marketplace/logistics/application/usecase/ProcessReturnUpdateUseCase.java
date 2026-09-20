package com.transformersas.marketplace.logistics.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.ReturnDeliveredToSeller;
import com.transformersas.marketplace.logistics.application.dto.UpdateResult;
import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.ReturnStatus;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingEvent;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingUpdate;
import com.transformersas.marketplace.logistics.domain.model.ReturnTransitions;
import com.transformersas.marketplace.logistics.domain.model.TrackingConflictException;
import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.logistics.domain.model.TrackingSource;
import com.transformersas.marketplace.logistics.domain.repository.ReturnShipmentRepository;
import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.web.CorrelationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Procesa una actualización logística del retorno de una devolución, llegue por webhook o por consulta (RF-110,
 * A1 a A5, RNF-043, RNF-046). Es el ÚNICO punto por el que cambia el estado de transporte de una devolución.
 *
 * <p>Identifica la devolución por su referencia logística y verifica la guía; después, en UNA transacción: guarda la
 * actualización (UNIQUE por devolución y evento: un evento repetido no tiene efecto), evalúa la máquina de estados
 * con el estado leído dentro de la transacción y aplica el cambio con compare-and-set junto con auditoría y
 * notificaciones. El tercer intento de recogida fallido detiene las recogidas y cierra la consulta. La entrega al
 * vendedor cierra CU-25 y publica ReturnDeliveredToSeller para que CU-19 continúe. NO llama a servicios externos.
 */
@Service
public class ProcessReturnUpdateUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessReturnUpdateUseCase.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ReturnShipmentRepository returns;
    private final PublishNotificationUseCase notifications;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    public ProcessReturnUpdateUseCase(ReturnShipmentRepository returns, PublishNotificationUseCase notifications,
                                      AuditRecorder audit, ApplicationEventPublisher events,
                                      TransactionTemplate transaction) {
        this.returns = returns;
        this.notifications = notifications;
        this.audit = audit;
        this.events = events;
        this.transaction = transaction;
    }

    public UpdateResult execute(ReturnTrackingUpdate update, TrackingSource source) {
        ReturnShipment found = returns.findByProviderReturnId(update.returnId()).orElseThrow(() ->
                BusinessException.notFound("RETURN_SHIPMENT_NOT_FOUND",
                        "No existe una devolución con esa referencia logística"));
        if (!found.trackingCode().equals(update.trackingCode())) {
            log.warn("Actualización con guía distinta returnId={} eventId={}", found.returnId(), update.eventId());
            audit.record(ActorType.LOGISTICS, null, "TRACKING_UPDATE_REJECTED", "RETURN", found.returnId(),
                    AuditOutcome.FAILURE, Map.of("eventId", update.eventId(), "reason", "TRACKING_CODE_MISMATCH"));
            throw BusinessException.conflict("RETURN_SHIPMENT_MISMATCH",
                    "La actualización no corresponde a esa devolución");
        }

        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> apply(found.id(), update, source));
            } catch (TrackingConflictException | ConcurrencyFailureException conflict) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Conflicto persistente al aplicar la actualización returnId={} eventId={}",
                            found.returnId(), update.eventId());
                    throw BusinessException.conflict("RETURN_STATE_CONFLICT",
                            "La devolución cambió de estado mientras se procesaba la actualización; se puede reenviar");
                }
            }
        }
    }

    private UpdateResult apply(Long returnShipmentId, ReturnTrackingUpdate update, TrackingSource source) {
        // Primer paso de la transacción: las actualizaciones de la misma devolución se procesan de una en una.
        returns.lock(returnShipmentId);
        LocalDateTime occurredAt = LocalDateTime.ofInstant(update.occurredAt(), ZoneId.systemDefault());
        var insertion = returns.insertEventIfAbsent(new ReturnTrackingEvent(null, returnShipmentId, update.eventId(),
                update.type(), occurredAt, LocalDateTime.now(), source, TrackingOutcome.RECORDED,
                update.description(), update.location(), update.evidence(), CorrelationContext.current()));
        if (!insertion.created()) {
            log.info("Actualización repetida omitida returnShipmentId={} eventId={}", returnShipmentId,
                    update.eventId());
            return UpdateResult.DUPLICATE;
        }

        // Estado leído dentro de la transacción: la decisión se toma sobre lo que realmente hay guardado.
        ReturnShipment current = returns.findById(returnShipmentId).orElseThrow();
        var evaluation = ReturnTransitions.evaluate(current, update.type());
        TrackingOutcome outcome = switch (evaluation.result()) {
            case REJECT -> TrackingOutcome.OUT_OF_ORDER;
            case RECORD -> TrackingOutcome.RECORDED;
            case APPLY -> TrackingOutcome.APPLIED;
        };

        if (outcome == TrackingOutcome.APPLIED) {
            ReturnStatus target = evaluation.target();
            boolean pickedUp = target == ReturnStatus.PICKED_UP || target == ReturnStatus.IN_RETURN;
            var change = new ReturnShipmentRepository.Change(target, evaluation.failedPickups(),
                    evaluation.pickupStopped(), pickedUp ? occurredAt : null,
                    target == ReturnStatus.DELIVERED_TO_SELLER ? occurredAt : null);
            if (!returns.applyChange(current.id(), current.status(), change)) {
                throw new TrackingConflictException("La devolución", current.returnId());
            }
            afterApplied(current, evaluation, update, occurredAt);
        } else if (outcome == TrackingOutcome.OUT_OF_ORDER) {
            log.warn("Actualización fuera de orden conservada returnId={} tipo={} estado={}", current.returnId(),
                    update.type(), current.status());
        }
        if (outcome != TrackingOutcome.RECORDED) {
            returns.updateEventOutcome(insertion.event().id(), outcome);
        }
        return UpdateResult.valueOf(outcome.name());
    }

    private void afterApplied(ReturnShipment current, ReturnTransitions.Evaluation evaluation,
                              ReturnTrackingUpdate update, LocalDateTime occurredAt) {
        ReturnStatus target = evaluation.target();
        audit.record(ActorType.LOGISTICS, null, "RETURN_STATUS_CHANGED", "RETURN", current.returnId(),
                AuditOutcome.SUCCESS, Map.of("from", current.status().name(), "to", target.name(),
                        "eventId", update.eventId()));
        boolean stopped = evaluation.pickupStopped();
        if (stopped) {
            // Tercer intento fallido: se detienen las recogidas y la consulta hasta que CU-19 gestione el caso.
            returns.closeTracking(current.id());
            audit.record(ActorType.LOGISTICS, null, "RETURN_PICKUP_STOPPED", "RETURN", current.returnId(),
                    AuditOutcome.SUCCESS, Map.of("failedPickups", evaluation.failedPickups(),
                            "eventId", update.eventId()));
        }
        notify(current, target, stopped, update.eventId());
        if (target == ReturnStatus.DELIVERED_TO_SELLER) {
            returns.closeTracking(current.id());
            events.publishEvent(new ReturnDeliveredToSeller(current.returnId(), current.buyerAccountId(),
                    current.storeId(), occurredAt));
        }
        log.info("Devolución actualizada returnId={} {} -> {} intentosFallidos={}", current.returnId(),
                current.status(), target, evaluation.failedPickups());
    }

    /** Comprador y vendedor reciben avisos solo sobre lo relevante; el texto nunca lleva datos personales. */
    private void notify(ReturnShipment shipment, ReturnStatus target, boolean stopped, String eventId) {
        switch (target) {
            case PICKUP_PENDING -> publish(shipment, RecipientType.BUYER, target, eventId,
                    "Nueva recogida programada", "Se programó una nueva recogida de tu devolución.");
            case PICKED_UP -> publish(shipment, RecipientType.BUYER, target, eventId,
                    "Recogimos tu devolución", "El servicio logístico recogió el producto de tu devolución.");
            case IN_RETURN -> publish(shipment, RecipientType.BUYER, target, eventId,
                    "Tu devolución va hacia el vendedor", "El producto de tu devolución está en camino al vendedor.");
            case LOGISTICS_ISSUE -> publish(shipment, RecipientType.BUYER, target, eventId,
                    "Novedad en tu devolución", "Hubo una novedad logística; consulta el detalle del seguimiento.");
            case PICKUP_FAILED -> {
                if (stopped) {
                    String message = "La recogida falló tres veces y el retorno no pudo continuar; el caso será gestionado.";
                    publish(shipment, RecipientType.BUYER, target, eventId, "No se pudo recoger tu devolución", message);
                    publish(shipment, RecipientType.STORE, target, eventId, "Retorno detenido", message);
                } else {
                    publish(shipment, RecipientType.BUYER, target, eventId, "No pudimos recoger tu devolución",
                            "La recogida falló. Puedes elegir otra opción de recogida si está disponible.");
                }
            }
            case DELIVERED_TO_SELLER -> {
                String message = "El producto de la devolución fue entregado al vendedor.";
                publish(shipment, RecipientType.BUYER, target, eventId, "Devolución entregada al vendedor", message);
                publish(shipment, RecipientType.STORE, target, eventId, "Devolución recibida", message);
            }
        }
    }

    private void publish(ReturnShipment shipment, RecipientType recipient, ReturnStatus target, String eventId,
                         String title, String message) {
        String key = "return-" + shipment.returnId() + "-" + target + "-" + eventId
                + (recipient == RecipientType.STORE ? "-STORE" : "");
        notifications.execute(new PublishNotificationCommand(recipient,
                recipient == RecipientType.BUYER ? shipment.buyerAccountId() : shipment.storeId(),
                "RETURN_" + target, title, message, "RETURN", String.valueOf(shipment.returnId()), key));
    }
}
