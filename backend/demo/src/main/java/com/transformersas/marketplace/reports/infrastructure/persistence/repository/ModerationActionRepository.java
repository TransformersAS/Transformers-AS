package com.transformersas.marketplace.reports.infrastructure.persistence.repository;

import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ModerationActionRepository extends JpaRepository<ModerationActionEntity, Long> {

    List<ModerationActionEntity> findByCaseIdOrderByCreatedAtAscIdAsc(Long caseId);

    List<ModerationActionEntity> findByCaseIdInOrderByCreatedAtAscIdAsc(Collection<Long> caseIds);
}
