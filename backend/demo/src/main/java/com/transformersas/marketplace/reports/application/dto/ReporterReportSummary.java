package com.transformersas.marketplace.reports.application.dto;

import java.time.LocalDateTime;

/** Fila del listado "Mis reportes"; no incluye la descripción ni carga imágenes. */
public record ReporterReportSummary(Long id, Long caseId, String contentType, String contentId, String reason,
                                    String reasonLabel, String status, LocalDateTime createdAt, int evidenceCount,
                                    boolean awaitingYourResponse) {
}
