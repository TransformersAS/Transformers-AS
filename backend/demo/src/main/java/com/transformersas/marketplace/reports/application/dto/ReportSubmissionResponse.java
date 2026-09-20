package com.transformersas.marketplace.reports.application.dto;

import java.time.LocalDateTime;

/**
 * Reporte radicado, con el identificador que recibe el usuario (RF-147). {@code duplicate} es verdadero cuando ya
 * tenía un reporte activo con el mismo motivo sobre ese contenido y se devuelve ese en vez de crear otro.
 */
public record ReportSubmissionResponse(Long id, Long caseId, String status, String contentType, String contentId,
                                       String reason, String reasonLabel, LocalDateTime createdAt,
                                       int evidenceCount, boolean duplicate) {
}
