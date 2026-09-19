package com.transformersas.marketplace.reports.application.dto;

import com.transformersas.marketplace.reports.domain.model.ContentModerationState;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationCaseEntity;

import java.time.LocalDateTime;

/** Fila de la cola de soporte: un caso que agrupa todos los reportes de un contenido (RF-156). */
public record ReportResponse(
        Long id,
        String contentType,
        String contentId,
        String status,
        int reportCount,
        String assignedAgentId,
        String contentState,
        LocalDateTime openedAt,
        LocalDateTime resolvedAt,
        Long version) {

    public static ReportResponse from(ModerationCaseEntity entity, ContentModerationState contentState) {
        return new ReportResponse(
                entity.getId(),
                entity.getContentType().name(),
                entity.getContentId(),
                entity.getStatus().name(),
                entity.getReportCount(),
                entity.getAssignedAgentId(),
                contentState.name(),
                entity.getOpenedAt(),
                entity.getResolvedAt(),
                entity.getVersion());
    }
}
