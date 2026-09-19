package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.audit.application.AuditService;
import com.transformersas.marketplace.reports.application.ModerationNotificationService.AgentNoticePayload;
import com.transformersas.marketplace.reports.application.ModerationNotificationService.InformationRequestPayload;
import com.transformersas.marketplace.reports.application.dto.ReportDetailResponse.InformationRequestItem;
import com.transformersas.marketplace.reports.application.dto.RequestInfoRequest;
import com.transformersas.marketplace.reports.domain.model.InfoRequestStatus;
import com.transformersas.marketplace.reports.domain.model.InfoRequestTarget;
import com.transformersas.marketplace.reports.domain.port.ContentSnapshot;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.InformationRequestEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationCaseEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.InformationRequestRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationCaseRepository;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Solicitudes de información al reportador o al propietario, con plazo de respuesta de 72 horas (RF-158). */
@Service
public class InformationRequestService {

    public static final Duration RESPONSE_DEADLINE = Duration.ofHours(72);

    private final ModerationCaseRepository caseRepository;
    private final InformationRequestRepository requestRepository;
    private final ReportRepository reportRepository;
    private final ContentSnapshotService snapshotService;
    private final ModerationNotificationService notifications;
    private final AuditService auditService;
    private final Clock clock;

    public InformationRequestService(ModerationCaseRepository caseRepository,
                                     InformationRequestRepository requestRepository,
                                     ReportRepository reportRepository, ContentSnapshotService snapshotService,
                                     ModerationNotificationService notifications, AuditService auditService,
                                     Clock clock) {
        this.caseRepository = caseRepository;
        this.requestRepository = requestRepository;
        this.reportRepository = reportRepository;
        this.snapshotService = snapshotService;
        this.notifications = notifications;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public InformationRequestItem request(Long caseId, String agentId, RequestInfoRequest command) {
        ModerationCaseEntity moderationCase = lockCase(caseId);
        List<String> reporterIds = reportRepository.findByCaseIdOrderByCreatedAtAscIdAsc(caseId).stream()
                .map(ReportEntity::getReporterId).distinct().toList();
        ReporterIdentityGuard.ensureAbsent(command.message(), reporterIds);

        moderationCase.ensureWorkableBy(agentId);
        String targetUserId = resolveTarget(moderationCase, command, reporterIds);
        if (requestRepository.existsByCaseIdAndTargetUserIdAndStatus(caseId, targetUserId,
                InfoRequestStatus.ABIERTA)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya hay una solicitud de información abierta para ese destinatario.");
        }
        moderationCase.markInformationRequested();

        LocalDateTime now = LocalDateTime.now(clock);
        InformationRequestEntity entity = new InformationRequestEntity();
        entity.setCaseId(caseId);
        entity.setTarget(command.target());
        entity.setTargetUserId(targetUserId);
        entity.setMessage(command.message());
        entity.setRequestedBy(agentId);
        entity.setRequestedAt(now);
        entity.setDueAt(now.plus(RESPONSE_DEADLINE));
        entity.setStatus(InfoRequestStatus.ABIERTA);
        entity = requestRepository.save(entity);
        caseRepository.save(moderationCase);

        auditService.logAction(agentId, "INFORMATION_REQUESTED", "MODERATION_CASE", caseId.toString(), "SUCCESS",
                Map.of("requestId", entity.getId(), "target", command.target().name(),
                        "targetUserId", targetUserId, "dueAt", entity.getDueAt().toString()));
        notifications.notifyUser(caseId, targetUserId, ModerationNotificationService.INFORMATION_REQUESTED,
                new InformationRequestPayload(entity.getId(), moderationCase.getContentType().name(),
                        moderationCase.getContentId(), command.message(), entity.getDueAt()));
        return toItem(entity);
    }

    /**
     * Registra la respuesta del reportador o propietario. Si el plazo ya venció (aunque la tarea
     * periódica no haya corrido) la solicitud se marca VENCIDA y se responde 410; ese cambio no se
     * revierte.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public InformationRequestItem respond(Long requestId, String userId, String text) {
        InformationRequestEntity request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Solicitud de información no encontrada"));
        if (!request.getTargetUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La solicitud no está dirigida a este usuario.");
        }
        ModerationCaseEntity moderationCase = lockCase(request.getCaseId());
        LocalDateTime now = LocalDateTime.now(clock);

        if (request.getStatus() == InfoRequestStatus.ABIERTA && now.isAfter(request.getDueAt())) {
            expire(request, moderationCase);
        }
        if (request.getStatus() == InfoRequestStatus.VENCIDA) {
            throw new ResponseStatusException(HttpStatus.GONE, "El plazo para responder ya venció.");
        }
        if (request.getStatus() == InfoRequestStatus.RESPONDIDA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La solicitud ya fue respondida.");
        }

        request.setStatus(InfoRequestStatus.RESPONDIDA);
        request.setRespondedAt(now);
        request.setResponseText(text);
        requestRepository.save(request);
        resumeIfNoneOpen(moderationCase);

        auditService.logAction(userId, "INFORMATION_RESPONDED", "MODERATION_CASE", moderationCase.getId().toString(),
                "SUCCESS", Map.of("requestId", request.getId()));
        notifyAgent(moderationCase, ModerationNotificationService.INFORMATION_RESPONDED, request.getId());
        return toItem(request);
    }

    /** Vence todas las solicitudes cuyo plazo terminó; lo ejecuta la tarea periódica. */
    @Transactional
    public int expireOverdue() {
        List<InformationRequestEntity> overdue = requestRepository.findByStatusAndDueAtBefore(
                InfoRequestStatus.ABIERTA, LocalDateTime.now(clock));
        for (InformationRequestEntity request : overdue) {
            expire(request, lockCase(request.getCaseId()));
        }
        return overdue.size();
    }

    /** Vence las solicitudes vencidas de un caso ya bloqueado; se usa antes de decidir. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expireOverdueForCase(ModerationCaseEntity moderationCase) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (InformationRequestEntity request : requestRepository.findByCaseIdAndStatus(moderationCase.getId(),
                InfoRequestStatus.ABIERTA)) {
            if (now.isAfter(request.getDueAt())) {
                expire(request, moderationCase);
            }
        }
    }

    /** ¿Queda una solicitud abierta y todavía vigente? */
    @Transactional(readOnly = true)
    public boolean hasActiveRequest(Long caseId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return requestRepository.findByCaseIdAndStatus(caseId, InfoRequestStatus.ABIERTA).stream()
                .anyMatch(request -> !now.isAfter(request.getDueAt()));
    }

    public static InformationRequestItem toItem(InformationRequestEntity entity) {
        return new InformationRequestItem(entity.getId(), entity.getTarget().name(), entity.getTargetUserId(),
                entity.getMessage(), entity.getRequestedBy(), entity.getRequestedAt(), entity.getDueAt(),
                entity.getStatus().name(), entity.getRespondedAt(), entity.getResponseText());
    }

    private void expire(InformationRequestEntity request, ModerationCaseEntity moderationCase) {
        request.setStatus(InfoRequestStatus.VENCIDA);
        requestRepository.save(request);
        resumeIfNoneOpen(moderationCase);
        auditService.logAction(null, "INFORMATION_EXPIRED", "MODERATION_CASE", moderationCase.getId().toString(),
                "SUCCESS", Map.of("requestId", request.getId(), "dueAt", request.getDueAt().toString()));
        notifyAgent(moderationCase, ModerationNotificationService.INFORMATION_EXPIRED, request.getId());
    }

    private void resumeIfNoneOpen(ModerationCaseEntity moderationCase) {
        if (!requestRepository.existsByCaseIdAndStatus(moderationCase.getId(), InfoRequestStatus.ABIERTA)) {
            moderationCase.resumeReview();
            caseRepository.save(moderationCase);
        }
    }

    private void notifyAgent(ModerationCaseEntity moderationCase, String template, Long requestId) {
        if (moderationCase.getAssignedAgentId() != null) {
            notifications.notifyInternal(moderationCase.getId(), moderationCase.getAssignedAgentId(), template,
                    new AgentNoticePayload(moderationCase.getId(), requestId));
        }
    }

    private String resolveTarget(ModerationCaseEntity moderationCase, RequestInfoRequest command,
                                 List<String> reporterIds) {
        if (command.target() == InfoRequestTarget.PROPIETARIO) {
            return snapshotService.find(moderationCase.getContentType(), moderationCase.getContentId())
                    .map(ContentSnapshot::ownerId)
                    .filter(owner -> owner != null && !owner.isBlank())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "No se pudo determinar al propietario del contenido."));
        }
        Optional<String> requested = Optional.ofNullable(command.targetUserId()).filter(id -> !id.isBlank());
        if (requested.isPresent()) {
            if (!reporterIds.contains(requested.get())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El usuario indicado no reportó este contenido.");
            }
            return requested.get();
        }
        if (reporterIds.size() == 1) {
            return reporterIds.get(0);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Indique a cuál reportador se dirige la solicitud (targetUserId).");
    }

    private ModerationCaseEntity lockCase(Long caseId) {
        return caseRepository.lockById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Caso no encontrado"));
    }
}
