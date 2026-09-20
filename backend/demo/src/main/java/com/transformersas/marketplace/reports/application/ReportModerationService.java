package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.audit.application.AuditService;
import com.transformersas.marketplace.audit.application.AuditService.AuditEntry;
import com.transformersas.marketplace.reports.application.dto.PageResponse;
import com.transformersas.marketplace.reports.application.dto.ReportDetailResponse;
import com.transformersas.marketplace.reports.application.dto.ReportDetailResponse.DecisionItem;
import com.transformersas.marketplace.reports.application.dto.ReportDetailResponse.EvidenceItem;
import com.transformersas.marketplace.reports.application.dto.ReportDetailResponse.HistoryItem;
import com.transformersas.marketplace.reports.application.dto.ReportDetailResponse.ReportItem;
import com.transformersas.marketplace.reports.application.dto.ReportResponse;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.model.ReportStatus;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationActionEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationCaseEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationReferralEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEvidenceEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.InformationRequestRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationActionRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationCaseRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationReferralRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReportEvidenceRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReportRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Consulta y gestión de la cola de casos de moderación (RF-156, RF-157). */
@Service
public class ReportModerationService {

    private static final String ENTITY_TYPE = "MODERATION_CASE";
    private static final List<ReportStatus> OPEN_STATUSES = List.of(ReportStatus.PENDIENTE,
            ReportStatus.EN_REVISION, ReportStatus.INFO_SOLICITADA);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ModerationCaseRepository caseRepository;
    private final ReportRepository reportRepository;
    private final ReportEvidenceRepository evidenceRepository;
    private final ModerationActionRepository actionRepository;
    private final InformationRequestRepository requestRepository;
    private final ModerationReferralRepository referralRepository;
    private final ContentModerationStateService contentState;
    private final ContentSnapshotService snapshotService;
    private final AuditService auditService;
    private final Clock clock;

    public ReportModerationService(ModerationCaseRepository caseRepository, ReportRepository reportRepository,
                                   ReportEvidenceRepository evidenceRepository,
                                   ModerationActionRepository actionRepository,
                                   InformationRequestRepository requestRepository,
                                   ModerationReferralRepository referralRepository,
                                   ContentModerationStateService contentState,
                                   ContentSnapshotService snapshotService, AuditService auditService, Clock clock) {
        this.caseRepository = caseRepository;
        this.reportRepository = reportRepository;
        this.evidenceRepository = evidenceRepository;
        this.actionRepository = actionRepository;
        this.requestRepository = requestRepository;
        this.referralRepository = referralRepository;
        this.contentState = contentState;
        this.snapshotService = snapshotService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Cola de casos. Sin filtros muestra los casos abiertos, primero los de más reportes y, a igual
     * número, los más antiguos.
     */
    @Transactional(readOnly = true)
    public PageResponse<ReportResponse> list(ReportStatus status, ReportContentType contentType, int page,
                                             int size) {
        List<ReportStatus> statuses = status == null ? OPEN_STATUSES : List.of(status);
        List<ReportContentType> types = contentType == null ? Arrays.asList(ReportContentType.values())
                : List.of(contentType);
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Sort order = Sort.by(Sort.Order.desc("reportCount"), Sort.Order.asc("openedAt"), Sort.Order.asc("id"));
        return PageResponse.from(caseRepository.search(statuses, types, PageRequest.of(Math.max(page, 0), safeSize,
                order)).map(entity -> ReportResponse.from(entity,
                        contentState.stateOf(entity.getContentType(), entity.getContentId()))));
    }

    @Transactional(readOnly = true)
    public ReportDetailResponse detail(Long caseId) {
        ModerationCaseEntity moderationCase = find(caseId);

        List<ReportEntity> reports = reportRepository.findByCaseIdOrderByCreatedAtAscIdAsc(caseId);
        Map<Long, List<EvidenceItem>> evidenceByReport = evidenceRepository
                .findByReportIdIn(reports.stream().map(ReportEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(ReportEvidenceEntity::getReportId,
                        Collectors.mapping(evidence -> new EvidenceItem(evidence.getId(), evidence.getFileUrl(),
                                evidence.getFileType(), evidence.getFileSize()), Collectors.toList())));
        List<ReportItem> reportItems = reports.stream()
                .map(report -> new ReportItem(report.getId(), report.getReporterId(), report.getReason(),
                        report.getDescription(), report.getCreatedAt(),
                        evidenceByReport.getOrDefault(report.getId(), List.of())))
                .toList();

        List<ModerationCaseEntity> previous = caseRepository
                .findByContentTypeAndContentIdAndStatusOrderByResolvedAtDesc(moderationCase.getContentType(),
                        moderationCase.getContentId(), ReportStatus.RESUELTO).stream()
                .filter(other -> !other.getId().equals(caseId)).toList();
        Map<Long, List<DecisionItem>> decisionsByCase = actionRepository
                .findByCaseIdInOrderByCreatedAtAscIdAsc(
                        Stream.concat(Stream.of(caseId), previous.stream().map(ModerationCaseEntity::getId))
                                .toList())
                .stream().collect(Collectors.groupingBy(ModerationActionEntity::getCaseId,
                        Collectors.mapping(ReportModerationService::toDecisionItem, Collectors.toList())));
        List<HistoryItem> history = previous.stream()
                .map(other -> new HistoryItem(other.getId(), other.getOpenedAt(), other.getResolvedAt(),
                        other.getReportCount(), decisionsByCase.getOrDefault(other.getId(), List.of())))
                .toList();

        return new ReportDetailResponse(
                moderationCase.getId(),
                moderationCase.getContentType().name(),
                moderationCase.getContentId(),
                moderationCase.getStatus().name(),
                moderationCase.getVersion(),
                moderationCase.getAssignedAgentId(),
                contentState.stateOf(moderationCase.getContentType(), moderationCase.getContentId()).name(),
                moderationCase.getReportCount(),
                moderationCase.getOpenedAt(),
                moderationCase.getResolvedAt(),
                snapshotService.find(moderationCase.getContentType(), moderationCase.getContentId()).orElse(null),
                reportItems,
                requestRepository.findByCaseIdOrderByRequestedAtAscIdAsc(caseId).stream()
                        .map(InformationRequestService::toItem).toList(),
                decisionsByCase.getOrDefault(caseId, List.of()),
                history);
    }

    /** El agente toma el caso; repetirlo siendo el mismo agente no cambia nada. */
    @Transactional
    public ReportResponse claim(Long caseId, String agentId) {
        ModerationCaseEntity moderationCase = caseRepository.lockById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso no encontrado"));
        boolean alreadyMine = agentId.equals(moderationCase.getAssignedAgentId()) && moderationCase.isOpen();
        moderationCase.ensureWorkableBy(agentId);
        if (!alreadyMine) {
            caseRepository.saveAndFlush(moderationCase);
            auditService.logAction(agentId, "CASE_CLAIMED", ENTITY_TYPE, caseId.toString(), "SUCCESS", Map.of());
        }
        return ReportResponse.from(moderationCase,
                contentState.stateOf(moderationCase.getContentType(), moderationCase.getContentId()));
    }

    /**
     * Remite el caso a la administración de cuentas (CU-22) cuando justifica una sanción de cuenta.
     * Solo deja constancia: CU-21 no suspende ni restringe cuentas.
     */
    @Transactional
    public Long referToAccountAdmin(Long caseId, String agentId, String justification) {
        find(caseId);
        ModerationReferralEntity referral = new ModerationReferralEntity();
        referral.setCaseId(caseId);
        referral.setAgentId(agentId);
        referral.setJustification(justification);
        referral.setStatus("PENDIENTE");
        referral.setCreatedAt(LocalDateTime.now(clock));
        referral = referralRepository.save(referral);
        auditService.logAction(agentId, "REFERRED_TO_ACCOUNT_ADMIN", ENTITY_TYPE, caseId.toString(), "SUCCESS",
                Map.of("referralId", referral.getId(), "justification", justification));
        return referral.getId();
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> auditTrail(Long caseId) {
        find(caseId);
        return auditService.history(ENTITY_TYPE, caseId.toString());
    }

    private ModerationCaseEntity find(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso no encontrado"));
    }

    private static DecisionItem toDecisionItem(ModerationActionEntity action) {
        return new DecisionItem(action.getId(), action.getAgentId(), action.getDecision().name(),
                action.getJustification(), action.getMeasureResult().name(), action.getCreatedAt());
    }
}
