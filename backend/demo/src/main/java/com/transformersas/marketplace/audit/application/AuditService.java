package com.transformersas.marketplace.audit.application;

import com.transformersas.marketplace.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.transformersas.marketplace.audit.infrastructure.persistence.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Registra la acción dentro de la transacción del llamador: la auditoría y el cambio que
     * describe se confirman o se revierten juntos, así nunca queda un "SUCCESS" de algo que no
     * ocurrió. El metadata se serializa con Jackson, nunca se arma concatenando texto.
     */
    @Transactional
    public void logAction(String actorId, String action, String entityType, String entityId, String result,
            Map<String, ?> metadata) {
        AuditLogEntity log = new AuditLogEntity();
        log.setActorId(actorId != null ? actorId : "SYSTEM");
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setResult(result);
        log.setMetadata(metadata == null ? null : objectMapper.writeValueAsString(metadata));

        auditLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> history(String entityType, String entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByIdAsc(entityType, entityId).stream()
                .map(log -> new AuditEntry(log.getId(), log.getActorId(), log.getAction(), log.getResult(),
                        log.getMetadata() == null ? null : objectMapper.readTree(log.getMetadata()),
                        log.getCreatedAt()))
                .toList();
    }

    public record AuditEntry(Long id, String actorId, String action, String result, JsonNode metadata,
                             java.time.LocalDateTime createdAt) {
    }
}
