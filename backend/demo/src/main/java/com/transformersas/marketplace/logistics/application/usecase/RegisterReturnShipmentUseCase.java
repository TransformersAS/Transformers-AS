package com.transformersas.marketplace.logistics.application.usecase;

import com.transformersas.marketplace.logistics.application.dto.RegisterReturnShipmentCommand;
import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.ReturnStatus;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingEvent;
import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.logistics.domain.model.TrackingSource;
import com.transformersas.marketplace.logistics.domain.repository.ReturnShipmentRepository;
import com.transformersas.marketplace.shared.audit.ActorType;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.web.CorrelationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Punto de entrada de CU-19: inicia el seguimiento logístico de una devolución aprobada cuyo retorno ya tiene
 * referencia logística (pasos 1 y 2 de CU-25). La devolución queda en Recogida pendiente. Es idempotente: registrar
 * dos veces la misma devolución devuelve el seguimiento existente sin duplicar nada.
 */
@Service
public class RegisterReturnShipmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterReturnShipmentUseCase.class);

    private final ReturnShipmentRepository returns;
    private final AuditRecorder audit;
    private final TransactionTemplate transaction;

    public RegisterReturnShipmentUseCase(ReturnShipmentRepository returns, AuditRecorder audit,
                                         TransactionTemplate transaction) {
        this.returns = returns;
        this.audit = audit;
        this.transaction = transaction;
    }

    /** Devuelve el seguimiento; created es false si la devolución ya estaba registrada. */
    public Registration execute(RegisterReturnShipmentCommand command) {
        return transaction.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            var insertion = returns.insertIfAbsent(new ReturnShipment(null, command.returnId(),
                    command.buyerAccountId(), command.storeId(), command.providerReturnId(), command.trackingCode(),
                    ReturnStatus.PICKUP_PENDING, 0, false, null, null, now, now));
            if (insertion.created()) {
                ReturnShipment shipment = insertion.shipment();
                // Primera entrada de la línea de tiempo: la devolución queda en Recogida pendiente.
                returns.insertEventIfAbsent(new ReturnTrackingEvent(null, shipment.id(),
                        "registered-" + command.returnId(), ReturnEventType.PICKUP_SCHEDULED, now, now,
                        TrackingSource.SYSTEM, TrackingOutcome.APPLIED, "Devolución registrada en logística", null,
                        null, CorrelationContext.current()));
                audit.record(ActorType.SYSTEM, null, "RETURN_SHIPMENT_REGISTERED", "RETURN", command.returnId(),
                        AuditOutcome.SUCCESS, Map.of("providerReturnId", command.providerReturnId(),
                                "trackingCode", command.trackingCode()));
                log.info("Seguimiento de devolución registrado returnId={}", command.returnId());
            }
            return new Registration(insertion.shipment(), insertion.created());
        });
    }

    public record Registration(ReturnShipment shipment, boolean created) {
    }
}
