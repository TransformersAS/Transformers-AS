package com.transformersas.marketplace.audit.infrastructure.persistence.repository;

import com.transformersas.marketplace.audit.infrastructure.persistence.entity.AuditLogEntity;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Solo inserción y consulta: la bitácora es append-only, por eso no extiende JpaRepository (que
 * expondría delete y saveAll sobre filas existentes).
 */
public interface AuditLogRepository extends Repository<AuditLogEntity, Long> {

    AuditLogEntity save(AuditLogEntity entry);

    List<AuditLogEntity> findByEntityTypeAndEntityIdOrderByIdAsc(String entityType, String entityId);
}
