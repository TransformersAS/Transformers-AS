package com.transformersas.marketplace.reports.infrastructure.persistence.repository;

import com.transformersas.marketplace.reports.domain.model.NotificationChannel;
import com.transformersas.marketplace.reports.domain.model.NotificationStatus;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ModerationNotificationRepository extends JpaRepository<ModerationNotificationEntity, Long> {

    List<ModerationNotificationEntity> findTop50ByChannelAndStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            NotificationChannel channel, NotificationStatus status, LocalDateTime now);
}
