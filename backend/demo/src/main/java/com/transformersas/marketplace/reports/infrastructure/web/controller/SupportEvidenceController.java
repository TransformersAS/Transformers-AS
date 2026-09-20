package com.transformersas.marketplace.reports.infrastructure.web.controller;

import com.transformersas.marketplace.reports.application.ReportEvidenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * Evidencia de un reporte para el agente que lo revisa (CU-21). Solo lectura; el rol SOPORTE lo exige la configuración de
 * seguridad para todo {@code /api/support/**}.
 */
@RestController
@RequestMapping("/api/support/moderation/reports/{reportId}/evidences")
public class SupportEvidenceController {
    private final ReportEvidenceService evidence;

    public SupportEvidenceController(ReportEvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/{ordinal}")
    public ResponseEntity<byte[]> evidence(WebRequest request, @PathVariable Long reportId,
                                           @PathVariable int ordinal) {
        return EvidenceResponses.serve(request, evidence.forSupport(reportId, ordinal));
    }
}
