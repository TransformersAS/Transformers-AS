package com.transformersas.marketplace.shared.audit;

import com.transformersas.marketplace.shared.web.CorrelationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Registro append-only de auditoría (RNF-009). Solo inserta. Se une a la transacción del llamador si existe
 * (el rollback la descarta junto con el cambio) o abre una propia (resultados posteriores al commit).
 * details no debe contener datos personales.
 */
@Component
public class AuditRecorder {

    private static final int MAX_DETAILS = 2000;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditRecorder(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void record(ActorType actorType, Long actorId, String action, String entityType, Object entityId,
                       AuditOutcome outcome, Map<String, ?> details) {
        jdbc.sql("""
                        INSERT INTO audit_events (occurred_at, actor_type, actor_id, action, entity_type, entity_id,
                                                  outcome, correlation_id, details)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(LocalDateTime.now(), actorType.name(), actorId, action, entityType, String.valueOf(entityId),
                        outcome.name(), CorrelationContext.current(), serialize(details))
                .update();
    }

    private String serialize(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        String value = json.writeValueAsString(details);
        return value.length() <= MAX_DETAILS ? value : value.substring(0, MAX_DETAILS);
    }
}
