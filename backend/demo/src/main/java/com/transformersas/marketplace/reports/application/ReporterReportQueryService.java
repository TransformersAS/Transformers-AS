package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.application.ReporterAccess.Reporter;
import com.transformersas.marketplace.reports.application.dto.ReporterReportDetail;
import com.transformersas.marketplace.reports.application.dto.ReporterReportDetail.EvidenceView;
import com.transformersas.marketplace.reports.application.dto.ReporterReportDetail.InformationRequestView;
import com.transformersas.marketplace.reports.application.dto.ReporterReportSummary;
import com.transformersas.marketplace.reports.domain.model.InfoRequestStatus;
import com.transformersas.marketplace.reports.domain.model.InfoRequestTarget;
import com.transformersas.marketplace.reports.domain.model.ReportReason;
import com.transformersas.marketplace.reports.domain.model.ReportStatus;
import com.transformersas.marketplace.reports.domain.model.ReporterOutcome;
import com.transformersas.marketplace.reports.domain.model.ReporterStatus;
import com.transformersas.marketplace.reports.domain.port.ReportEvidenceStorage;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.InformationRequestEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationActionEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationCaseEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEvidenceEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.InformationRequestRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationActionRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationCaseRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReportEvidenceRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReporterReportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lo que ve y puede hacer quien radicó un reporte (RF-148, RF-149): listar y consultar los suyos y responder a las
 * solicitudes de información que le dirige el agente. Todo se acota al autor: un reporte ajeno responde 404, igual que
 * uno inexistente. Nunca se expone la identidad de otros reportantes, la del agente ni su justificación.
 */
@Service
public class ReporterReportQueryService {
    static final int MAX_RESPONSE = 4000;

    private final ReporterAccess access;
    private final ReporterReportRepository reports;
    private final ModerationCaseRepository cases;
    private final ReportEvidenceRepository evidences;
    private final ReportEvidenceStorage evidenceStorage;
    private final InformationRequestRepository requests;
    private final ModerationActionRepository actions;
    private final InformationRequestService informationRequests;
    private final Clock clock;

    public ReporterReportQueryService(ReporterAccess access, ReporterReportRepository reports,
                                      ModerationCaseRepository cases, ReportEvidenceRepository evidences,
                                      ReportEvidenceStorage evidenceStorage, InformationRequestRepository requests,
                                      ModerationActionRepository actions,
                                      InformationRequestService informationRequests, Clock clock) {
        this.access = access;
        this.reports = reports;
        this.cases = cases;
        this.evidences = evidences;
        this.evidenceStorage = evidenceStorage;
        this.requests = requests;
        this.actions = actions;
        this.informationRequests = informationRequests;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ReporterReportSummary> list(Authentication authentication) {
        Reporter reporter = access.require(authentication);
        List<ReportEntity> mine = reports.findByReporterIdOrderByCreatedAtDescIdDesc(reporter.id());
        Map<Long, ModerationCaseEntity> caseById = cases.findAllById(mine.stream().map(ReportEntity::getCaseId)
                .distinct().toList()).stream().collect(Collectors.toMap(ModerationCaseEntity::getId,
                Function.identity()));
        Map<Long, Long> evidenceCount = evidences.findByReportIdIn(mine.stream().map(ReportEntity::getId).toList())
                .stream().collect(Collectors.groupingBy(ReportEvidenceEntity::getReportId, Collectors.counting()));
        LocalDateTime now = LocalDateTime.now(clock);
        return mine.stream().map(report -> {
            ModerationCaseEntity moderationCase = caseById.get(report.getCaseId());
            return new ReporterReportSummary(report.getId(), report.getCaseId(), report.getContentType().name(),
                    report.getContentId(), report.getReason(), reasonLabel(report),
                    ReporterStatus.from(moderationCase.getStatus()).name(), report.getCreatedAt(),
                    evidenceCount.getOrDefault(report.getId(), 0L).intValue(),
                    awaiting(report.getCaseId(), reporter, now));
        }).toList();
    }

    @Transactional(readOnly = true)
    public ReporterReportDetail detail(Authentication authentication, Long reportId) {
        Reporter reporter = access.require(authentication);
        ReportEntity report = ownReport(reporter, reportId);
        ModerationCaseEntity moderationCase = cases.findById(report.getCaseId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now(clock);
        List<EvidenceView> images = evidenceStorage.summaries(report.getId()).stream()
                .map(image -> new EvidenceView(image.ordinal(), image.fileName(), image.contentType(),
                        image.sizeBytes())).toList();
        List<InformationRequestView> asked = requests.findByCaseIdOrderByRequestedAtAscIdAsc(report.getCaseId())
                .stream().filter(request -> isForReporter(request, reporter))
                .map(request -> view(request, now)).toList();
        return new ReporterReportDetail(report.getId(), report.getCaseId(), report.getContentType().name(),
                report.getContentId(), report.getReason(), reasonLabel(report), report.getDescription(),
                ReporterStatus.from(moderationCase.getStatus()).name(), result(moderationCase),
                report.getCreatedAt(), moderationCase.getResolvedAt(), images, asked);
    }

    /** Responde una solicitud del agente dirigida a este reportante (RF-148); el plazo y sus reglas son los de CU-21. */
    public InformationRequestView respond(Authentication authentication, Long reportId, Long requestId, String text) {
        Reporter reporter = access.require(authentication);
        ReportEntity report = ownReport(reporter, reportId);
        InformationRequestEntity request = requests.findById(requestId)
                .filter(found -> found.getCaseId().equals(report.getCaseId()) && isForReporter(found, reporter))
                .orElseThrow(() -> ReportException.notFound("Solicitud de información no encontrada"));
        String answer = text == null || text.isBlank() ? null : text.strip();
        if (answer == null) {
            throw ReportException.field("text", "REPORT_FIELD_REQUIRED", "Escribe tu respuesta");
        }
        if (answer.length() > MAX_RESPONSE) {
            throw ReportException.field("text", "REPORT_FIELD_INVALID",
                    "La respuesta admite hasta " + MAX_RESPONSE + " caracteres");
        }
        try {
            informationRequests.respond(request.getId(), reporter.id(), answer);
        } catch (ResponseStatusException rejected) {
            throw translate(rejected);
        }
        return view(requests.findById(requestId).orElseThrow(), LocalDateTime.now(clock));
    }

    private ReportEntity ownReport(Reporter reporter, Long reportId) {
        return reports.findByIdAndReporterId(reportId, reporter.id())
                .orElseThrow(() -> ReportException.notFound("Reporte no encontrado"));
    }

    private static boolean isForReporter(InformationRequestEntity request, Reporter reporter) {
        return request.getTarget() == InfoRequestTarget.REPORTADOR && request.getTargetUserId().equals(reporter.id());
    }

    private boolean awaiting(Long caseId, Reporter reporter, LocalDateTime now) {
        return requests.findByCaseIdAndStatus(caseId, InfoRequestStatus.ABIERTA).stream()
                .anyMatch(request -> isForReporter(request, reporter) && !now.isAfter(request.getDueAt()));
    }

    /** Solo un caso resuelto tiene resultado; es el de la última decisión del agente. */
    private String result(ModerationCaseEntity moderationCase) {
        if (moderationCase.getStatus() != ReportStatus.RESUELTO) {
            return null;
        }
        List<ModerationActionEntity> decisions = actions.findByCaseIdOrderByCreatedAtAscIdAsc(moderationCase.getId());
        return decisions.isEmpty() ? null
                : ReporterOutcome.from(decisions.get(decisions.size() - 1).getDecision()).name();
    }

    private static InformationRequestView view(InformationRequestEntity request, LocalDateTime now) {
        InfoRequestStatus status = request.getStatus() == InfoRequestStatus.ABIERTA && now.isAfter(request.getDueAt())
                ? InfoRequestStatus.VENCIDA : request.getStatus();
        return new InformationRequestView(request.getId(), request.getMessage(), request.getRequestedAt(),
                request.getDueAt(), status.name(), request.getRespondedAt(), request.getResponseText());
    }

    private static String reasonLabel(ReportEntity report) {
        return ReportReason.fromCode(report.getReason()).map(ReportReason::label).orElse(report.getReason());
    }

    private static ReportException translate(ResponseStatusException rejected) {
        HttpStatus status = HttpStatus.valueOf(rejected.getStatusCode().value());
        String code = switch (status) {
            case GONE -> "INFORMATION_REQUEST_EXPIRED";
            case CONFLICT -> "INFORMATION_REQUEST_ALREADY_ANSWERED";
            default -> "INFORMATION_REQUEST_REJECTED";
        };
        return new ReportException(status, code, rejected.getReason());
    }
}
