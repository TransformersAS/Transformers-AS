package com.transformersas.marketplace.reports.infrastructure.persistence.repository;

import com.transformersas.marketplace.reports.domain.model.InfoRequestStatus;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.InformationRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InformationRequestRepository extends JpaRepository<InformationRequestEntity, Long> {

    List<InformationRequestEntity> findByCaseIdOrderByRequestedAtAscIdAsc(Long caseId);

    List<InformationRequestEntity> findByCaseIdAndStatus(Long caseId, InfoRequestStatus status);

    boolean existsByCaseIdAndStatus(Long caseId, InfoRequestStatus status);

    boolean existsByCaseIdAndTargetUserIdAndStatus(Long caseId, String targetUserId, InfoRequestStatus status);

    List<InformationRequestEntity> findByStatusAndDueAtBefore(InfoRequestStatus status, LocalDateTime limit);
}
