package com.transformersas.marketplace.restrictions.application.usecase;

import com.transformersas.marketplace.audit.application.AuditService;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bitácora de las acciones administrativas sobre cuentas (RNF-009): actor, fecha, acción, cuenta afectada y
 * resultado. Se escribe en la transacción de la acción, así nunca queda registrada una medida que no se aplicó.
 */
@Component
public class RestrictionAudit {

    static final String ENTITY = "ACCOUNT";

    private final AuditService audit;

    public RestrictionAudit(AuditService audit) {
        this.audit = audit;
    }

    /** {@code actorId} nulo significa que actuó el sistema (el fin automático). */
    public void record(Long actorId, String action, Long accountId, Map<String, ?> details) {
        audit.logAction(actorId == null ? null : String.valueOf(actorId), action, ENTITY, String.valueOf(accountId),
                "SUCCESS", details);
    }
}
