package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionChange;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionScope;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionStatus;
import com.transformersas.marketplace.restrictions.domain.port.StoreOperationalLink;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;
import com.transformersas.marketplace.users.domain.model.Role;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Termina una restricción vigente, sea porque el administrador la retira (A6) o porque se cumplió su fin (A5). Si
 * dejaba de vender por ella (toda la cuenta o el rol VENDEDOR) y la cuenta ya no tiene otra restricción que también
 * se lo impida, su tienda vuelve a operar (pasos 19 y 20).
 */
@Component
public class RestrictionClosing {

    public record Outcome(Long restrictionId, RestrictionStatus status) {
    }

    private final AccountRestrictionRepository restrictions;
    private final StoreOperationalLink storeLink;
    private final RestrictionAudit audit;
    private final RestrictionNotices notices;

    public RestrictionClosing(AccountRestrictionRepository restrictions, StoreOperationalLink storeLink,
                              RestrictionAudit audit, RestrictionNotices notices) {
        this.restrictions = restrictions;
        this.storeLink = storeLink;
        this.audit = audit;
        this.notices = notices;
    }

    /**
     * Cierra la restricción como WITHDRAWN (con el administrador y su motivo) o EXPIRED (actorId nulo). Lanza
     * CONFLICT RESTRICTION_CONCURRENT_UPDATE si otro proceso la cambió o cerró antes; nada queda a medias.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome close(AccountRestriction restriction, RestrictionStatus status, Long actorId, String reason,
                         LocalDateTime now) {
        if (!restrictions.close(restriction.id(), restriction.version(), status, now, actorId, reason)) {
            throw BusinessException.conflict("RESTRICTION_CONCURRENT_UPDATE",
                    "La restricción cambió mientras se procesaba; vuelve a consultarla");
        }
        RestrictionChange.Type type = status == RestrictionStatus.WITHDRAWN
                ? RestrictionChange.Type.WITHDRAWN : RestrictionChange.Type.EXPIRED;
        restrictions.addChange(new RestrictionChange(null, restriction.id(), type, restriction.endsAt(), now, reason,
                actorId, now));
        endStoreEffectIfNeeded(restriction);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("restrictionId", restriction.id());
        details.put("reason", reason);
        audit.record(actorId, type == RestrictionChange.Type.WITHDRAWN ? "ACCOUNT_RESTRICTION_WITHDRAWN"
                : "ACCOUNT_RESTRICTION_EXPIRED", restriction.accountId(), details);
        notices.restrictionEnded(restriction, type == RestrictionChange.Type.WITHDRAWN);
        return new Outcome(restriction.id(), status);
    }

    /** La restricción ya quedó cerrada en la base; si ninguna otra sigue impidiendo vender, libera la tienda. */
    private void endStoreEffectIfNeeded(AccountRestriction restriction) {
        boolean affectedSelling = restriction.scope() == RestrictionScope.ACCOUNT || restriction.role() == Role.VENDEDOR;
        if (!affectedSelling || restrictions.hasActiveRestriction(restriction.accountId(), Role.VENDEDOR)) {
            return;
        }
        storeLink.storeOwnedBy(restriction.accountId()).ifPresent(storeLink::endRestriction);
    }
}
