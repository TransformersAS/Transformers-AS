package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.application.ReporterAccess.Reporter;
import com.transformersas.marketplace.reports.domain.port.ReportEvidenceStorage;
import com.transformersas.marketplace.reports.domain.port.ReportEvidenceStorage.EvidenceContent;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ReporterReportRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entrega las imágenes de evidencia de un reporte: a quien lo radicó y, por separado, a soporte (CU-21). Un
 * reportante que pide la evidencia de un reporte ajeno recibe el mismo 404 que si no existiera.
 */
@Service
public class ReportEvidenceService {
    private final ReporterAccess access;
    private final ReporterReportRepository reports;
    private final ReportEvidenceStorage storage;

    public ReportEvidenceService(ReporterAccess access, ReporterReportRepository reports,
                                 ReportEvidenceStorage storage) {
        this.access = access;
        this.reports = reports;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public EvidenceContent forReporter(Authentication authentication, Long reportId, int ordinal) {
        Reporter reporter = access.require(authentication);
        reports.findByIdAndReporterId(reportId, reporter.id())
                .orElseThrow(() -> ReportException.notFound("Reporte no encontrado"));
        return find(reportId, ordinal);
    }

    /** Sin comprobar autoría: solo lo invoca el controlador de soporte, protegido por el rol SOPORTE. */
    @Transactional(readOnly = true)
    public EvidenceContent forSupport(Long reportId, int ordinal) {
        return find(reportId, ordinal);
    }

    private EvidenceContent find(Long reportId, int ordinal) {
        return storage.find(reportId, ordinal)
                .orElseThrow(() -> ReportException.notFound("Evidencia no encontrada"));
    }
}
