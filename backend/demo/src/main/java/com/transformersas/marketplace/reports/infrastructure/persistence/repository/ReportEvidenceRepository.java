package com.transformersas.marketplace.reports.infrastructure.persistence.repository;

import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ReportEvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ReportEvidenceRepository extends JpaRepository<ReportEvidenceEntity, Long> {

    List<ReportEvidenceEntity> findByReportId(Long reportId);

    List<ReportEvidenceEntity> findByReportIdIn(Collection<Long> reportIds);
}
