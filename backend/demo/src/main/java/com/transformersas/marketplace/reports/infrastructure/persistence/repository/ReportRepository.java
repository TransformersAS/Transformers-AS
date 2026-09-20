package com.transformersas.marketplace.reports.infrastructure.persistence.repository;

import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportRepository extends JpaRepository<ReportEntity, Long> {

    List<ReportEntity> findByCaseIdOrderByCreatedAtAscIdAsc(Long caseId);

    boolean existsByCaseIdAndReporterId(Long caseId, String reporterId);
}
