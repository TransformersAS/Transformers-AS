package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.suspensions.domain.model.ClarificationRequest;
import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Avisos al vendedor sobre su tienda (postcondición 7). Cada uno queda como notificación interna en la misma
 * transacción que la medida y, después del commit, se pide el aviso externo. Si el servicio externo falla la medida ya
 * está aplicada y la notificación interna sigue disponible (A10). La clave del evento evita avisar dos veces lo mismo.
 */
@Component
public class SuspensionNotices {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String REFERENCE = "STORE";

    private final PublishNotificationUseCase notifications;

    public SuspensionNotices(PublishNotificationUseCase notifications) {
        this.notifications = notifications;
    }

    void clarificationRequested(ClarificationRequest request, Long requestId) {
        publish(request.storeId(), "STORE_CLARIFICATION_REQUESTED", "La administración pide una aclaración",
                "Tienes hasta el " + DATE.format(request.dueAt()) + " para responder en «Estado de mi tienda»: "
                        + request.message(),
                "store-clarification-" + requestId);
    }

    void suspensionApplied(StoreSuspension suspension, Long suspensionId) {
        publish(suspension.storeId(), "STORE_SUSPENDED", "Tu tienda fue suspendida temporalmente",
                "Hasta el " + DATE.format(suspension.endsAt()) + " no podrás publicar ni recibir nuevas compras; "
                        + "sigue atendiendo tus pedidos, reclamaciones y devoluciones. Motivo: " + suspension.reason(),
                "store-suspension-" + suspensionId + "-APPLIED");
    }

    void durationChanged(StoreSuspension suspension, LocalDateTime newEndsAt, Long changeId, String justification) {
        publish(suspension.storeId(), "STORE_SUSPENSION_CHANGED", "Cambió la duración de la suspensión",
                "La suspensión de tu tienda ahora termina el " + DATE.format(newEndsAt) + ". Motivo del cambio: "
                        + justification,
                "store-suspension-" + suspension.id() + "-CHANGED-" + changeId);
    }

    void suspensionEnded(StoreSuspension suspension, boolean reactivated, boolean lifted) {
        String title = reactivated ? "Tu tienda volvió a operar" : "Terminó la suspensión de tu tienda";
        String message = (lifted ? "La administración levantó la suspensión antes de su fin. "
                : "Se cumplió el plazo de la suspensión. ")
                + (reactivated ? "Ya puedes publicar y recibir compras de nuevo."
                : "Tu tienda sigue sin poder operar porque tu cuenta tiene otra restricción vigente.");
        publish(suspension.storeId(), "STORE_SUSPENSION_ENDED", title, message,
                "store-suspension-" + suspension.id() + "-ENDED");
    }

    private void publish(Long storeId, String type, String title, String message, String eventKey) {
        String text = message.length() > 1000 ? message.substring(0, 999) + "…" : message;
        notifications.execute(new PublishNotificationCommand(RecipientType.STORE, storeId, type, title, text,
                REFERENCE, String.valueOf(storeId), eventKey));
    }
}
