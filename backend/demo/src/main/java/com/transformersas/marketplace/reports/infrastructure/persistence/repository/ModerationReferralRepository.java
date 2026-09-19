package com.transformersas.marketplace.reports.infrastructure.persistence.repository;

import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationReferralEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ModerationReferralRepository extends JpaRepository<ModerationReferralEntity, Long> {
}
