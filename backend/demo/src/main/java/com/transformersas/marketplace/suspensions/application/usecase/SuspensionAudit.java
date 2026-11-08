package com.transformersas.marketplace.suspensions.application.usecase;

import com.transformersas.marketplace.audit.application.AuditService;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bitácora de las acciones administrativas sobre tiendas (RNF-009): actor, fecha, acción, tienda afectada y
 * resultado. Se escribe en la transacción de la acción, así nunca queda registrada una medida que no se aplicó.
 */
@Component
public class SuspensionAudit {

    static final String ENTITY = "STORE";

    private final AuditService audit;

    public SuspensionAudit(AuditService audit) {
        this.audit = audit;
    }

    /** {@code actorId} nulo significa que actuó el sistema (el fin automático). */
    public void record(Long actorId, String action, Long storeId, Map<String, ?> details) {
        audit.logAction(actorId == null ? null : String.valueOf(actorId), action, ENTITY, String.valueOf(storeId),
                "SUCCESS", details);
    }
}
