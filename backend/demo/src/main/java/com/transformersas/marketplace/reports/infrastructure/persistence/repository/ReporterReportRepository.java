package com.transformersas.marketplace.reports.infrastructure.persistence.repository;

import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Consultas de reportes desde el lado de quien los radicó (CU-20); siempre acotadas a su autor. */
@Repository
public interface ReporterReportRepository extends JpaRepository<ReportEntity, Long> {

    List<ReportEntity> findByCaseIdAndReporterIdOrderByIdAsc(Long caseId, String reporterId);

    List<ReportEntity> findByReporterIdOrderByCreatedAtDescIdDesc(String reporterId);

    Optional<ReportEntity> findByIdAndReporterId(Long id, String reporterId);
}
