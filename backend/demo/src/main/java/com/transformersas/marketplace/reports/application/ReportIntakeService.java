package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.audit.application.AuditService;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationCaseEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEvidenceEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationCaseRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReportEvidenceRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Punto de entrada para CU-20 (radicar reportes): guarda el reporte individual, con su autor, y lo
 * agrupa en el caso abierto del contenido (RF-156), creándolo si no existe.
 */
@Service
public class ReportIntakeService {

    public record EvidenceInput(String fileUrl, String fileType, Long fileSize) {
    }

    public record SubmitReport(String reporterId, ReportContentType contentType, String contentId, String reason,
                               String description, List<EvidenceInput> evidences) {
    }

    public record Intake(Long caseId, Long reportId) {
    }

    private final ModerationCaseRepository caseRepository;
    private final ReportRepository reportRepository;
    private final ReportEvidenceRepository evidenceRepository;
    private final AuditService auditService;
    private final Clock clock;

    public ReportIntakeService(ModerationCaseRepository caseRepository, ReportRepository reportRepository,
                               ReportEvidenceRepository evidenceRepository, AuditService auditService,
                               Clock clock) {
        this.caseRepository = caseRepository;
        this.reportRepository = reportRepository;
        this.evidenceRepository = evidenceRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public Intake submit(SubmitReport command) {
        if (isBlank(command.reporterId()) || isBlank(command.contentId()) || isBlank(command.reason())
                || command.contentType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Reportador, tipo y id de contenido y motivo son obligatorios.");
        }

        // Un mismo usuario no suma dos veces al conteo de un caso abierto.
        caseRepository.findByContentTypeAndContentIdAndOpenKey(command.contentType(), command.contentId(), 1)
                .filter(open -> reportRepository.existsByCaseIdAndReporterId(open.getId(), command.reporterId()))
                .ifPresent(open -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Ya reportaste este contenido y el caso sigue abierto.");
                });

        LocalDateTime now = LocalDateTime.now(clock);
        caseRepository.openOrAddReport(command.contentType().name(), command.contentId(), now);
        // La fila del caso queda bloqueada por esta transacción tras el upsert: nadie puede resolverla
        // antes de que se inserte el reporte.
        ModerationCaseEntity moderationCase = caseRepository
                .findByContentTypeAndContentIdAndOpenKey(command.contentType(), command.contentId(), 1)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "El caso cambió mientras se radicaba el reporte; intente de nuevo."));

        ReportEntity report = new ReportEntity();
        report.setCaseId(moderationCase.getId());
        report.setReporterId(command.reporterId());
        report.setContentType(command.contentType());
        report.setContentId(command.contentId());
        report.setReason(command.reason());
        report.setDescription(command.description());
        report.setCreatedAt(now);
        report = reportRepository.save(report);

        if (command.evidences() != null) {
            for (EvidenceInput input : command.evidences()) {
                ReportEvidenceEntity evidence = new ReportEvidenceEntity();
                evidence.setReportId(report.getId());
                evidence.setFileUrl(input.fileUrl());
                evidence.setFileType(input.fileType());
                evidence.setFileSize(input.fileSize());
                evidenceRepository.save(evidence);
            }
        }

        auditService.logAction(command.reporterId(), "REPORT_SUBMITTED", "MODERATION_CASE",
                moderationCase.getId().toString(), "SUCCESS", Map.of(
                        "reportId", report.getId(),
                        "contentType", command.contentType().name(),
                        "contentId", command.contentId(),
                        "reason", command.reason()));
        return new Intake(moderationCase.getId(), report.getId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
