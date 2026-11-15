package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.notifications.application.dto.PublishNotificationCommand;
import com.transformersas.marketplace.notifications.application.usecase.PublishNotificationUseCase;
import com.transformersas.marketplace.notifications.domain.model.RecipientType;
import com.transformersas.marketplace.restrictions.domain.model.AccountInformationRequest;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.Texts;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Avisos a la persona titular de la cuenta (postcondición 8), sin exponer información administrativa de terceros.
 * Cada uno queda como notificación interna en la misma transacción que la medida y, después del commit, se pide el
 * aviso externo (A10). La clave del evento evita avisar dos veces lo mismo.
 *
 * <p>Se publican como {@link RecipientType#BUYER} con el id de la cuenta: es el canal genérico "por cuenta" que ya
 * usan las demás notificaciones propias, independiente del rol que tenga la restricción.
 */
@Component
public class RestrictionNotices {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String REFERENCE = "ACCOUNT";

    private final PublishNotificationUseCase notifications;

    public RestrictionNotices(PublishNotificationUseCase notifications) {
        this.notifications = notifications;
    }

    void informationRequested(AccountInformationRequest request, Long requestId) {
        publish(request.accountId(), "ACCOUNT_INFORMATION_REQUESTED", "La administración pide una aclaración",
                "Tienes hasta el " + DATE.format(request.dueAt()) + " para responder: " + request.message(),
                "account-information-" + requestId);
    }

    void restrictionApplied(AccountRestriction restriction, Long restrictionId) {
        String scope = scopeLabel(restriction);
        String when = restriction.permanent() ? "de forma permanente"
                : "hasta el " + DATE.format(restriction.endsAt());
        publish(restriction.accountId(), "ACCOUNT_RESTRICTED", "Tu cuenta tiene una restricción",
                scope + " queda restringido " + when + ". Motivo: " + restriction.reason(),
                "account-restriction-" + restrictionId + "-APPLIED");
    }

    void durationChanged(AccountRestriction restriction, Long changeId, String justification) {
        publish(restriction.accountId(), "ACCOUNT_RESTRICTION_CHANGED", "Cambió una restricción de tu cuenta",
                "La restricción ahora termina el " + DATE.format(restriction.endsAt()) + ". Motivo del cambio: "
                        + justification,
                "account-restriction-" + restriction.id() + "-CHANGED-" + changeId);
    }

    void restrictionEnded(AccountRestriction restriction, boolean withdrawn) {
        String title = withdrawn ? "Se retiró una restricción de tu cuenta" : "Terminó una restricción de tu cuenta";
        publish(restriction.accountId(), "ACCOUNT_RESTRICTION_ENDED", title,
                scopeLabel(restriction) + " ya no está restringido.",
                "account-restriction-" + restriction.id() + "-ENDED");
    }

    private static String scopeLabel(AccountRestriction restriction) {
        return restriction.role() == null ? "Tu cuenta" : "Tu rol de " + Texts.roleLabel(restriction.role());
    }

    private void publish(Long accountId, String type, String title, String message, String eventKey) {
        String text = message.length() > 1000 ? message.substring(0, 999) + "…" : message;
        notifications.execute(new PublishNotificationCommand(RecipientType.BUYER, accountId, type, title, text,
                REFERENCE, String.valueOf(accountId), eventKey));
    }
}
