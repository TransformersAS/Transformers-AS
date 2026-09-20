package com.transformersas.marketplace.reports.application.dto;

import java.time.LocalDateTime;

/**
 * Fila del listado "Mis reportes"; no incluye la descripción ni carga imágenes. {@code medidaProvisional} es OCULTO
 * mientras el contenido está oculto por precaución y el caso sigue abierto.
 */
public record ReporterReportSummary(Long id, Long caseId, String contentType, String contentId, String reason,
                                    String reasonLabel, String status, LocalDateTime createdAt, int evidenceCount,
                                    boolean awaitingYourResponse, String medidaProvisional) {
}
