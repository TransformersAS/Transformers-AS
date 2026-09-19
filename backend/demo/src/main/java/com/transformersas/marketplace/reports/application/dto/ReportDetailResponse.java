package com.transformersas.marketplace.reports.application.dto;

import com.transformersas.marketplace.reports.domain.port.ContentSnapshot;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detalle de un caso para soporte (RF-157). Incluye la identidad de quien reportó: esta vista es
 * exclusiva de soporte y jamás se reutiliza para notificar al responsable del contenido.
 */
public record ReportDetailResponse(
        Long id,
        String contentType,
        String contentId,
        String status,
        Long version,
        String assignedAgentId,
        String contentState,
        int reportCount,
        LocalDateTime openedAt,
        LocalDateTime resolvedAt,
        ContentSnapshot content,
        List<ReportItem> reports,
        List<InformationRequestItem> informationRequests,
        List<DecisionItem> decisions,
        List<HistoryItem> history) {

    public record ReportItem(Long id, String reporterId, String reason, String description,
                             LocalDateTime createdAt, List<EvidenceItem> evidences) {
    }

    public record EvidenceItem(Long id, String fileUrl, String fileType, Long fileSize) {
    }

    public record InformationRequestItem(Long id, String target, String targetUserId, String message,
                                         String requestedBy, LocalDateTime requestedAt, LocalDateTime dueAt,
                                         String status, LocalDateTime respondedAt, String responseText) {
    }

    public record DecisionItem(Long id, String agentId, String decision, String justification,
                               String measureResult, LocalDateTime createdAt) {
    }

    /** Caso anterior ya resuelto sobre el mismo contenido. */
    public record HistoryItem(Long caseId, LocalDateTime openedAt, LocalDateTime resolvedAt, int reportCount,
                              List<DecisionItem> decisions) {
    }
}
