package com.transformersas.marketplace.reports.infrastructure.web.controller;

import com.transformersas.marketplace.reports.application.ReporterAccess;
import com.transformersas.marketplace.reports.application.dto.ReportReasonResponse;
import com.transformersas.marketplace.reports.domain.model.ReportReason;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/** Radicación y consulta de reportes de contenido por parte de compradores y vendedores (CU-20). */
@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReporterAccess access;

    public ReportController(ReporterAccess access) {
        this.access = access;
    }

    /** Todos los motivos, con la bandera {@code purchaseProblem}: el formulario orienta hacia reclamaciones. */
    @GetMapping("/reasons")
    public List<ReportReasonResponse> reasons(Authentication authentication) {
        access.require(authentication);
        return Arrays.stream(ReportReason.values()).map(ReportReasonResponse::from).toList();
    }
}
