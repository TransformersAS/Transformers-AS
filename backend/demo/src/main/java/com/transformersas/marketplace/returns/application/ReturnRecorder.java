package com.transformersas.marketplace.returns.application;

import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.returns.domain.model.ReturnEvent;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.repository.ReturnRequestRepository;
import com.transformersas.marketplace.shared.audit.AuditOutcome;
import com.transformersas.marketplace.shared.audit.AuditRecorder;
import com.transformersas.marketplace.shared.web.CorrelationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deja constancia de un cambio de una devolución en la misma transacción que lo produce: línea de tiempo (RF-051),
 * auditoría (RNF-009) y notificación interna (RF-123). El aviso externo sale después del commit, así que un fallo de
 * esa notificación no revierte nada (A11). Las claves de notificación llevan el id de la devolución y del hecho, así un
 * evento repetido no avisa dos veces (A10).
 */
@Component
public class ReturnRecorder {
    private final ReturnRequestRepository repository;
    private final AuditRecorder audit;
    private final PublishNotificationUseCase notifications;

    ReturnRecorder(ReturnRequestRepository repository, AuditRecorder audit, PublishNotificationUseCase notifications) {
        this.repository = repository;
        this.audit = audit;
        this.notifications = notifications;
    }

    /** Guarda el hecho en la línea de tiempo y en la auditoría. */
    public void record(ReturnRequest request, ReturnEvent event, LocalDateTime at) {
        repository.appendEvent(request.getId(), event, CorrelationContext.current(), at);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("returnId", request.getId());
        details.put("orderId", request.getOrderId());
        if (event.from() != null) {
            details.put("from", event.from().name());
        }
        if (event.to() != null) {
            details.put("to", event.to().name());
        }
        audit.record(event.actorType(), event.actorId(), "RETURN_" + event.type().name(), "RETURN", request.getId(),
                AuditOutcome.SUCCESS, details);
    }

    /** Avisa a la tienda; {@code eventKey} distingue hechos repetibles (p. ej. cada solicitud de información). */
    public void notifyStore(ReturnRequest request, String event, String eventKey, String title, String message) {
        notifications.execute(new PublishNotificationCommand(RecipientType.STORE, request.getStoreId(),
                "RETURN_" + event, title, message, "RETURN", String.valueOf(request.getId()),
                "return-" + request.getId() + "-" + eventKey + "-STORE"));
    }

    public void notifyBuyer(ReturnRequest request, String event, String eventKey, String title, String message) {
        notifications.execute(new PublishNotificationCommand(RecipientType.BUYER, request.getBuyerAccountId(),
                "RETURN_" + event, title, message, "RETURN", String.valueOf(request.getId()),
                "return-" + request.getId() + "-" + eventKey));
    }
}
