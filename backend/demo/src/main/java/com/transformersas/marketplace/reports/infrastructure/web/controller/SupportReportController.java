package com.transformersas.marketplace.reports.infrastructure.web.controller;

import com.transformersas.marketplace.audit.application.AuditService.AuditEntry;
import com.transformersas.marketplace.auth.application.CurrentAccount;
import com.transformersas.marketplace.reports.application.InformationRequestService;
import com.transformersas.marketplace.reports.application.ModerationDecisionService;
import com.transformersas.marketplace.reports.application.ReportModerationService;
import com.transformersas.marketplace.reports.application.dto.DecisionResponse;
import com.transformersas.marketplace.reports.application.dto.InformationResponseRequest;
import com.transformersas.marketplace.reports.application.dto.ModerationRequest;
import com.transformersas.marketplace.reports.application.dto.PageResponse;
import com.transformersas.marketplace.reports.application.dto.ReferralRequest;
import com.transformersas.marketplace.reports.application.dto.ReportDetailResponse;
import com.transformersas.marketplace.reports.application.dto.ReportDetailResponse.InformationRequestItem;
import com.transformersas.marketplace.reports.application.dto.ReportResponse;
import com.transformersas.marketplace.reports.application.dto.RequestInfoRequest;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.model.ReportStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API de moderación para agentes de soporte (CU-21) y respuesta a solicitudes de información.
 *
 * <p>La identidad sale de la sesión autenticada (CU-08). El rol SOPORTE para {@code /api/support/**}
 * lo exige la cadena de seguridad; aquí solo se toma el id de la cuenta autenticada.
 */
@RestController
@RequestMapping("/api")
public class SupportReportController {

    private final ReportModerationService moderationService;
    private final InformationRequestService informationRequests;
    private final ModerationDecisionService decisions;

    public SupportReportController(ReportModerationService moderationService,
                                   InformationRequestService informationRequests,
                                   ModerationDecisionService decisions) {
        this.moderationService = moderationService;
        this.informationRequests = informationRequests;
        this.decisions = decisions;
    }

    @GetMapping("/support/moderation/cases")
    public ResponseEntity<PageResponse<ReportResponse>> listCases(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportContentType contentType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(moderationService.list(status, contentType, page, size));
    }

    @GetMapping("/support/moderation/cases/{caseId}")
    public ResponseEntity<ReportDetailResponse> getCase(
            @PathVariable Long caseId) {
        return ResponseEntity.ok(moderationService.detail(caseId));
    }

    @PostMapping("/support/moderation/cases/{caseId}/claim")
    public ResponseEntity<ReportResponse> claim(
            @PathVariable Long caseId,
            Authentication authentication) {
        String agentId = CurrentAccount.id(authentication);
        return ResponseEntity.ok(moderationService.claim(caseId, agentId));
    }

    @PostMapping("/support/moderation/cases/{caseId}/information-requests")
    public ResponseEntity<InformationRequestItem> requestInformation(
            @PathVariable Long caseId,
            @Valid @RequestBody RequestInfoRequest request,
            Authentication authentication) {
        String agentId = CurrentAccount.id(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(informationRequests.request(caseId, agentId, request));
    }

    @PostMapping("/support/moderation/cases/{caseId}/decisions")
    public ResponseEntity<DecisionResponse> decide(
            @PathVariable Long caseId,
            @Valid @RequestBody ModerationRequest request,
            Authentication authentication) {
        String agentId = CurrentAccount.id(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(decisions.decide(caseId, agentId, request));
    }

    @PostMapping("/support/moderation/cases/{caseId}/referrals/account-admin")
    public ResponseEntity<Map<String, Long>> referToAccountAdmin(
            @PathVariable Long caseId,
            @Valid @RequestBody ReferralRequest request,
            Authentication authentication) {
        String agentId = CurrentAccount.id(authentication);
        Long referralId = moderationService.referToAccountAdmin(caseId, agentId, request.justification());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("referralId", referralId));
    }

    @GetMapping("/support/moderation/cases/{caseId}/audit")
    public ResponseEntity<List<AuditEntry>> audit(
            @PathVariable Long caseId) {
        return ResponseEntity.ok(moderationService.auditTrail(caseId));
    }

    /** Lo usa quien recibió la solicitud (reportador o propietario), no un agente de soporte. */
    @PostMapping("/moderation/information-requests/{requestId}/response")
    public ResponseEntity<InformationRequestItem> respond(
            @PathVariable Long requestId,
            @Valid @RequestBody InformationResponseRequest request,
            Authentication authentication) {
        String userId = CurrentAccount.id(authentication);
        return ResponseEntity.ok(informationRequests.respond(requestId, userId, request.text()));
    }
}
