package com.transformersas.marketplace.reports.infrastructure.web.controller;

import com.transformersas.marketplace.reports.application.ReportEvidenceService;
import com.transformersas.marketplace.reports.application.ReportException;
import com.transformersas.marketplace.reports.application.ReportSubmissionService;
import com.transformersas.marketplace.reports.application.ReportSubmissionService.EvidenceUpload;
import com.transformersas.marketplace.reports.application.ReporterAccess;
import com.transformersas.marketplace.reports.application.ReporterReportQueryService;
import com.transformersas.marketplace.reports.application.dto.InformationResponseBody;
import com.transformersas.marketplace.reports.application.dto.ReportReasonResponse;
import com.transformersas.marketplace.reports.application.dto.ReportSubmissionResponse;
import com.transformersas.marketplace.reports.application.dto.ReporterReportDetail;
import com.transformersas.marketplace.reports.application.dto.ReporterReportDetail.InformationRequestView;
import com.transformersas.marketplace.reports.application.dto.ReporterReportSummary;
import com.transformersas.marketplace.reports.application.dto.SubmitReportRequest;
import com.transformersas.marketplace.reports.domain.model.ReportReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Radicación y consulta de reportes de contenido por parte de compradores y vendedores (CU-20). */
@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReporterAccess access;
    private final ReportSubmissionService submission;
    private final ReportEvidenceService evidence;
    private final ReporterReportQueryService queries;

    public ReportController(ReporterAccess access, ReportSubmissionService submission,
                            ReportEvidenceService evidence, ReporterReportQueryService queries) {
        this.access = access;
        this.submission = submission;
        this.evidence = evidence;
        this.queries = queries;
    }

    /** Todos los motivos, con la bandera {@code purchaseProblem}: el formulario orienta hacia reclamaciones. */
    @GetMapping("/reasons")
    public List<ReportReasonResponse> reasons(Authentication authentication) {
        access.require(authentication);
        return Arrays.stream(ReportReason.values()).map(ReportReasonResponse::from).toList();
    }

    /** Radica un reporte sin imágenes. 201 si es nuevo; 200 con el existente si ya tenía uno activo con ese motivo. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReportSubmissionResponse> submit(Authentication authentication,
                                                           @RequestBody SubmitReportRequest request) {
        return respond(submission.submit(authentication, request, List.of()));
    }

    /** Radica un reporte con imágenes: los campos del reporte como campos de formulario y las imágenes en {@code evidences}. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReportSubmissionResponse> submitWithEvidence(
            Authentication authentication,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) String contentId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String description,
            @RequestParam(name = "evidences", required = false) List<MultipartFile> evidences) {
        List<EvidenceUpload> uploads = evidences == null ? List.of() : evidences.stream()
                // Un formulario sin archivo elegido envía una parte vacía y sin nombre: no es una imagen.
                .filter(file -> !(file.isEmpty() && (file.getOriginalFilename() == null
                        || file.getOriginalFilename().isBlank())))
                .map(ReportController::upload).toList();
        return respond(submission.submit(authentication,
                new SubmitReportRequest(contentType, contentId, reason, description), uploads));
    }

    /** Los reportes del usuario, del más reciente al más antiguo. */
    @GetMapping("/mine")
    public List<ReporterReportSummary> mine(Authentication authentication) {
        return queries.list(authentication);
    }

    /** Detalle de un reporte propio; el de otra persona responde 404. */
    @GetMapping("/{reportId}")
    public ReporterReportDetail detail(Authentication authentication, @PathVariable Long reportId) {
        return queries.detail(authentication, reportId);
    }

    /** Responde una solicitud de información del agente dentro del plazo de 72 horas. */
    @PostMapping("/{reportId}/information-requests/{requestId}/response")
    public InformationRequestView respond(Authentication authentication, @PathVariable Long reportId,
                                          @PathVariable Long requestId, @RequestBody InformationResponseBody body) {
        return queries.respond(authentication, reportId, requestId, body.text());
    }

    /** Una imagen de evidencia de un reporte propio, con ETag; la de un reporte ajeno responde 404. */
    @GetMapping("/{reportId}/evidences/{ordinal}")
    public ResponseEntity<byte[]> evidence(WebRequest request, Authentication authentication,
                                           @PathVariable Long reportId, @PathVariable int ordinal) {
        return EvidenceResponses.serve(request, evidence.forReporter(authentication, reportId, ordinal));
    }

    private static ResponseEntity<ReportSubmissionResponse> respond(ReportSubmissionResponse body) {
        return ResponseEntity.status(body.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    private static EvidenceUpload upload(MultipartFile file) {
        try {
            return new EvidenceUpload(file.getOriginalFilename(), file.getBytes());
        } catch (IOException unreadable) {
            throw ReportException.field("evidences", "EVIDENCE_UNREADABLE", "No se pudo leer una de las imágenes");
        }
    }
}
