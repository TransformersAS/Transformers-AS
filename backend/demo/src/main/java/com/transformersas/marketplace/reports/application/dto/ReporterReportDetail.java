package com.transformersas.marketplace.reports.application.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detalle de un reporte propio (RF-149): estado, solicitudes de información dirigidas al reportante y, si el caso se
 * resolvió, solo el resultado. Mientras el caso está abierto y el contenido oculto por precaución,
 * {@code medidaProvisional} es OCULTO. No trae justificación, agente ni datos de otros reportantes o del propietario.
 */
public record ReporterReportDetail(Long id, Long caseId, String contentType, String contentId, String reason,
                                   String reasonLabel, String description, String status, String result,
                                   String medidaProvisional,
                                   LocalDateTime createdAt, LocalDateTime resolvedAt, List<EvidenceView> evidences,
                                   List<InformationRequestView> informationRequests) {

    public record EvidenceView(int ordinal, String fileName, String contentType, long sizeBytes) {
    }

    /** {@code status}: ABIERTA, RESPONDIDA o VENCIDA (esta última también cuando el plazo pasó y aún no se barrió). */
    public record InformationRequestView(Long id, String message, LocalDateTime requestedAt, LocalDateTime dueAt,
                                         String status, LocalDateTime respondedAt, String responseText) {
    }
}
