package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.audit.application.AuditService;
import com.transformersas.marketplace.reports.application.ContentModerationStateService.AppliedMeasure;
import com.transformersas.marketplace.reports.application.ModerationNotificationService.OwnerDecisionPayload;
import com.transformersas.marketplace.reports.application.ModerationNotificationService.ReporterOutcomePayload;
import com.transformersas.marketplace.reports.application.dto.DecisionResponse;
import com.transformersas.marketplace.reports.application.dto.ModerationRequest;
import com.transformersas.marketplace.reports.domain.model.ModerationDecision;
import com.transformersas.marketplace.reports.domain.model.ReportStatus;
import com.transformersas.marketplace.reports.domain.port.ContentSnapshot;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationActionEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationCaseEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationActionRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationCaseRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registra la decisión del agente y aplica la medida sobre el contenido (RF-159, RF-160), audita y
 * notifica (RF-161). Todo lo que cambia el estado ocurre en una sola transacción; el envío externo
 * queda en el outbox, así que un fallo del servicio de notificaciones no bloquea la moderación.
 *
 * <p>MANTENER y RETIRAR cierran el caso. OCULTAR_TEMPORALMENTE es una medida cautelar: oculta el
 * contenido y deja el caso abierto hasta la decisión final.
 */
@Service
public class ModerationDecisionService {

    private final ModerationCaseRepository caseRepository;
    private final ModerationActionRepository actionRepository;
    private final ReportRepository reportRepository;
    private final ContentModerationStateService contentState;
    private final ContentSnapshotService snapshotService;
    private final InformationRequestService informationRequests;
    private final ModerationNotificationService notifications;
    private final AuditService auditService;
    private final Clock clock;

    public ModerationDecisionService(ModerationCaseRepository caseRepository,
                                     ModerationActionRepository actionRepository, ReportRepository reportRepository,
                                     ContentModerationStateService contentState,
                                     ContentSnapshotService snapshotService,
                                     InformationRequestService informationRequests,
                                     ModerationNotificationService notifications, AuditService auditService,
                                     Clock clock) {
        this.caseRepository = caseRepository;
        this.actionRepository = actionRepository;
        this.reportRepository = reportRepository;
        this.contentState = contentState;
        this.snapshotService = snapshotService;
        this.informationRequests = informationRequests;
        this.notifications = notifications;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public DecisionResponse decide(Long caseId, String agentId, ModerationRequest request) {
        ModerationCaseEntity moderationCase = caseRepository.lockById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso no encontrado"));
        if (request.expectedVersion() != null && !request.expectedVersion().equals(moderationCase.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El caso cambió desde que lo consultó; recárguelo antes de decidir.");
        }
        ReportStatus previousStatus = moderationCase.getStatus();
        moderationCase.ensureWorkableBy(agentId);

        informationRequests.expireOverdueForCase(moderationCase);
        ModerationDecision decision = request.decision();
        if (decision != ModerationDecision.OCULTAR_TEMPORALMENTE
                && informationRequests.hasActiveRequest(caseId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Hay una solicitud de información vigente; espere la respuesta o el vencimiento del plazo.");
        }

        List<ReportEntity> reports = reportRepository.findByCaseIdOrderByCreatedAtAscIdAsc(caseId);
        List<String> reporterIds = reports.stream().map(ReportEntity::getReporterId).distinct().toList();
        ReporterIdentityGuard.ensureAbsent(request.justification(), reporterIds);

        AppliedMeasure measure = contentState.apply(moderationCase.getContentType(), moderationCase.getContentId(),
                decision, caseId);

        LocalDateTime now = LocalDateTime.now(clock);
        boolean closesCase = decision != ModerationDecision.OCULTAR_TEMPORALMENTE;
        if (closesCase) {
            moderationCase.resolve(now);
        }
        caseRepository.save(moderationCase);

        ModerationActionEntity action = new ModerationActionEntity();
        action.setCaseId(caseId);
        action.setAgentId(agentId);
        action.setDecision(decision);
        action.setJustification(request.justification());
        action.setMeasureResult(measure.result());
        action.setPreviousStatus(previousStatus);
        action.setNewStatus(moderationCase.getStatus());
        action.setCreatedAt(now);
        action = actionRepository.save(action);

        String ownerId = snapshotService.find(moderationCase.getContentType(), moderationCase.getContentId())
                .map(ContentSnapshot::ownerId).orElse(null);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("actionId", action.getId());
        metadata.put("decision", decision.name());
        metadata.put("justification", request.justification());
        metadata.put("measureResult", measure.result().name());
        metadata.put("contentStateBefore", measure.previous().name());
        metadata.put("contentStateAfter", measure.current().name());
        metadata.put("caseStatus", moderationCase.getStatus().name());
        metadata.put("ownerNotified", ownerId != null);
        auditService.logAction(agentId, "MODERATION_DECISION", "MODERATION_CASE", caseId.toString(), "SUCCESS",
                metadata);

        notifyParties(moderationCase, decision, request.justification(), ownerId, reports, closesCase);
        return new DecisionResponse(action.getId(), decision.name(), measure.result().name(),
                moderationCase.getStatus().name(), measure.current().name());
    }

    private void notifyParties(ModerationCaseEntity moderationCase, ModerationDecision decision,
                               String justification, String ownerId, List<ReportEntity> reports,
                               boolean closesCase) {
        String type = moderationCase.getContentType().name();
        if (ownerId != null) {
            List<String> categories = reports.stream().map(ReportEntity::getReason).distinct().toList();
            notifications.notifyUser(moderationCase.getId(), ownerId,
                    ModerationNotificationService.DECISION_TO_OWNER,
                    new OwnerDecisionPayload(type, moderationCase.getContentId(), decision.name(), justification,
                            categories));
        }
        if (closesCase) {
            String outcome = decision == ModerationDecision.RETIRAR ? "CONTENIDO_RETIRADO" : "CONTENIDO_MANTENIDO";
            reports.stream().map(ReportEntity::getReporterId).distinct()
                    .forEach(reporterId -> notifications.notifyUser(moderationCase.getId(), reporterId,
                            ModerationNotificationService.OUTCOME_TO_REPORTER,
                            new ReporterOutcomePayload(type, moderationCase.getContentId(), outcome)));
        }
    }
}
