package com.transformersas.marketplace.reports.infrastructure.persistence.repository;

import com.transformersas.marketplace.reports.domain.model.ContentModerationState;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ContentModerationStateEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ContentModerationStateId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContentModerationStateRepository
        extends JpaRepository<ContentModerationStateEntity, ContentModerationStateId> {

    List<ContentModerationStateEntity> findByContentTypeAndStateNot(
            ReportContentType contentType, ContentModerationState state);
}
