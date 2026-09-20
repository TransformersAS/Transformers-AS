package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.audit.application.AuditService;
import com.transformersas.marketplace.reports.application.ReportIntakeService.Intake;
import com.transformersas.marketplace.reports.application.ReportIntakeService.SubmitReport;
import com.transformersas.marketplace.reports.application.ReporterAccess.Reporter;
import com.transformersas.marketplace.reports.application.dto.ReportSubmissionResponse;
import com.transformersas.marketplace.reports.application.dto.SubmitReportRequest;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.model.ReportReason;
import com.transformersas.marketplace.reports.domain.model.ReporterStatus;
import com.transformersas.marketplace.reports.domain.port.ReportEvidenceStorage;
import com.transformersas.marketplace.reports.infrastructure.config.ReportEvidenceProperties;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationCaseEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEvidenceEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationCaseRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReportEvidenceRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReporterReportRepository;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.shared.files.ImageValidator;
import com.transformersas.marketplace.shared.files.ValidatedImage;
import com.transformersas.marketplace.shared.web.CorrelationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Radica un reporte de contenido (RF-145 a RF-147). Valida todo antes de escribir; el caso, el reporte, las imágenes y
 * la auditoría se guardan en una sola transacción (A10), así que un fallo no deja casos ni imágenes a medias. El
 * agrupamiento en el caso y la regla de un caso abierto por contenido los pone {@link ReportIntakeService} (CU-21).
 */
@Service
public class ReportSubmissionService {
    static final int MAX_DESCRIPTION = 2000;
    static final int MAX_CONTENT_ID = 255;
    private static final String EVIDENCE_URL = "/api/support/moderation/reports/%d/evidences/%d";

    /** Imagen adjunta tal como llega del cliente, antes de validarla. */
    public record EvidenceUpload(String fileName, byte[] data) {
    }

    private final ReporterAccess access;
    private final ReportableContentRegistry contents;
    private final ReportIntakeService intake;
    private final ModerationCaseRepository caseRepository;
    private final ReporterReportRepository reports;
    private final ReportEvidenceRepository evidences;
    private final ReportEvidenceStorage evidenceStorage;
    private final AuditService auditService;
    private final ImageValidator imageValidator;
    private final ReportEvidenceProperties limits;
    private final TransactionTemplate transaction;

    public ReportSubmissionService(ReporterAccess access, ReportableContentRegistry contents,
                                   ReportIntakeService intake, ModerationCaseRepository caseRepository,
                                   ReporterReportRepository reports, ReportEvidenceRepository evidences,
                                   ReportEvidenceStorage evidenceStorage, AuditService auditService,
                                   ReportEvidenceProperties limits, PlatformTransactionManager transactionManager) {
        this.access = access;
        this.contents = contents;
        this.intake = intake;
        this.caseRepository = caseRepository;
        this.reports = reports;
        this.evidences = evidences;
        this.evidenceStorage = evidenceStorage;
        this.auditService = auditService;
        this.limits = limits;
        this.imageValidator = new ImageValidator(limits.maxSize().toBytes(), ImageValidator.DEFAULT_MAX_SIDE,
                ImageValidator.DEFAULT_MAX_PIXELS);
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Radica el reporte; si ya había uno activo con el mismo motivo, devuelve ese (duplicate=true) sin crear otro. */
    public ReportSubmissionResponse submit(Authentication authentication, SubmitReportRequest request,
                                           List<EvidenceUpload> uploads) {
        Reporter reporter = access.require(authentication);
        Draft draft = validate(request);
        if (!contents.verifier(draft.type()).isAccessibleTo(draft.contentId(), reporter.id())) {
            throw new ReportException(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND",
                    "El contenido que quieres reportar no existe o no está disponible");
        }
        if (contents.ownerOf(draft.type(), draft.contentId()).filter(reporter.id()::equals).isPresent()) {
            throw new ReportException(HttpStatus.FORBIDDEN, "REPORT_OWN_CONTENT",
                    "No puedes reportar contenido tuyo");
        }
        Optional<ReportSubmissionResponse> existing = existingReport(reporter, draft);
        if (existing.isPresent()) {
            return existing.get();
        }
        List<ValidatedEvidence> validated = validateEvidence(uploads);
        try {
            return transaction.execute(status -> persist(reporter, draft, validated));
        } catch (ResponseStatusException raced) {
            // Otra petición del mismo usuario radicó entre la comprobación y la escritura: se resuelve igual.
            if (raced.getStatusCode() == HttpStatus.CONFLICT) {
                return existingReport(reporter, draft).orElseThrow(() -> raced);
            }
            throw raced;
        }
    }

    private record Draft(ReportContentType type, String contentId, ReportReason reason, String description) {
    }

    private record ValidatedEvidence(String fileName, ValidatedImage image, byte[] data) {
    }

    private Draft validate(SubmitReportRequest request) {
        if (request == null) {
            throw ReportException.field("contentType", "REPORT_FIELD_REQUIRED", "Indica el tipo de contenido");
        }
        String rawType = trimmed(request.contentType());
        if (rawType == null) {
            throw ReportException.field("contentType", "REPORT_FIELD_REQUIRED", "Indica el tipo de contenido");
        }
        ReportContentType type;
        try {
            type = ReportContentType.valueOf(rawType);
        } catch (IllegalArgumentException unknown) {
            throw ReportException.field("contentType", "REPORT_FIELD_INVALID", "El tipo de contenido no es válido");
        }
        String contentId = trimmed(request.contentId());
        if (contentId == null) {
            throw ReportException.field("contentId", "REPORT_FIELD_REQUIRED", "Indica qué contenido reportas");
        }
        if (contentId.length() > MAX_CONTENT_ID) {
            throw ReportException.field("contentId", "REPORT_FIELD_INVALID",
                    "El identificador del contenido no es válido");
        }
        String rawReason = trimmed(request.reason());
        if (rawReason == null) {
            throw ReportException.field("reason", "REPORT_FIELD_REQUIRED", "Elige el motivo del reporte");
        }
        ReportReason reason = ReportReason.fromCode(rawReason).orElseThrow(() -> ReportException.field("reason",
                "REPORT_FIELD_INVALID", "El motivo elegido no está en la lista de motivos"));
        String description = trimmed(request.description());
        if (description == null) {
            throw ReportException.field("description", "REPORT_FIELD_REQUIRED",
                    "Describe qué está mal en el contenido");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw ReportException.field("description", "REPORT_FIELD_INVALID",
                    "La descripción admite hasta " + MAX_DESCRIPTION + " caracteres");
        }
        if (reason.purchaseProblem()) {
            throw new ReportException(HttpStatus.UNPROCESSABLE_ENTITY, "REPORT_REASON_IS_PURCHASE_PROBLEM",
                    "Los problemas con una compra no se reportan como contenido: usa reclamaciones y devoluciones",
                    Map.of("field", "reason"));
        }
        contents.verifier(type);
        return new Draft(type, contentId, reason, description);
    }

    private List<ValidatedEvidence> validateEvidence(List<EvidenceUpload> uploads) {
        List<EvidenceUpload> files = uploads == null ? List.of() : uploads;
        if (files.size() > limits.maxCount()) {
            throw ReportException.field("evidences", "EVIDENCE_TOO_MANY",
                    "Puedes adjuntar hasta " + limits.maxCount() + " imágenes");
        }
        List<ValidatedEvidence> validated = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            EvidenceUpload upload = files.get(i);
            try {
                ValidatedImage image = imageValidator.validate(upload.data());
                validated.add(new ValidatedEvidence(fileName(upload.fileName(), i + 1, image), image, upload.data()));
            } catch (BusinessException invalid) {
                throw new ReportException(HttpStatus.BAD_REQUEST, invalid.code(),
                        "Imagen " + (i + 1) + ": " + invalid.getMessage(), Map.of("field", "evidences[" + i + "]"));
            }
        }
        return validated;
    }

    private ReportSubmissionResponse persist(Reporter reporter, Draft draft, List<ValidatedEvidence> files) {
        Intake created = intake.submit(new SubmitReport(reporter.id(), draft.type(), draft.contentId(),
                draft.reason().name(), draft.description(), List.of()));
        int ordinal = 0;
        for (ValidatedEvidence file : files) {
            ordinal++;
            ReportEvidenceEntity evidence = new ReportEvidenceEntity();
            evidence.setReportId(created.reportId());
            evidence.setFileUrl(EVIDENCE_URL.formatted(created.reportId(), ordinal));
            evidence.setFileType(file.image().contentType());
            evidence.setFileSize((long) file.data().length);
            evidence = evidences.save(evidence);
            evidenceStorage.save(evidence.getId(), created.reportId(), ordinal, file.fileName(),
                    file.image().contentType(), file.image().sha256(), file.data());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reportId", created.reportId());
        metadata.put("caseId", created.caseId());
        metadata.put("reason", draft.reason().name());
        metadata.put("evidenceCount", files.size());
        metadata.put("activeRole", reporter.role().name());
        metadata.put("correlationId", CorrelationContext.current());
        auditService.logAction(reporter.id(), "CONTENT_REPORT_FILED", "REPORT", created.reportId().toString(),
                "SUCCESS", metadata);
        ModerationCaseEntity moderationCase = caseRepository.findById(created.caseId()).orElseThrow();
        ReportEntity report = reports.findById(created.reportId()).orElseThrow();
        return toResponse(report, moderationCase, files.size(), false);
    }

    /** Un reporte activo del mismo usuario sobre ese contenido: mismo motivo, se devuelve; otro motivo, 409. */
    private Optional<ReportSubmissionResponse> existingReport(Reporter reporter, Draft draft) {
        return caseRepository.findByContentTypeAndContentIdAndOpenKey(draft.type(), draft.contentId(), 1)
                .flatMap(open -> {
                    List<ReportEntity> mine =
                            reports.findByCaseIdAndReporterIdOrderByIdAsc(open.getId(), reporter.id());
                    if (mine.isEmpty()) {
                        return Optional.empty();
                    }
                    Optional<ReportEntity> same = mine.stream()
                            .filter(report -> draft.reason().name().equals(report.getReason())).findFirst();
                    if (same.isEmpty()) {
                        Long reportId = mine.get(0).getId();
                        throw new ReportException(HttpStatus.CONFLICT, "REPORT_ALREADY_OPEN",
                                "Ya reportaste este contenido con otro motivo y el caso sigue abierto (reporte "
                                        + reportId + ")", Map.of("reportId", reportId));
                    }
                    ReportEntity report = same.get();
                    return Optional.of(
                            toResponse(report, open, evidenceStorage.summaries(report.getId()).size(), true));
                });
    }

    private ReportSubmissionResponse toResponse(ReportEntity report, ModerationCaseEntity moderationCase,
                                                int evidenceCount, boolean duplicate) {
        String reasonLabel = ReportReason.fromCode(report.getReason()).map(ReportReason::label)
                .orElse(report.getReason());
        return new ReportSubmissionResponse(report.getId(), moderationCase.getId(),
                ReporterStatus.from(moderationCase.getStatus()).name(), report.getContentType().name(),
                report.getContentId(), report.getReason(), reasonLabel, report.getCreatedAt(), evidenceCount,
                duplicate);
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Solo el nombre del archivo, sin rutas ni caracteres de control, y nunca vacío. */
    static String fileName(String original, int position, ValidatedImage image) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            name = "evidencia-" + position + ("image/png".equals(image.contentType()) ? ".png" : ".jpg");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
