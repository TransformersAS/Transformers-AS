package com.transformersas.marketplace.reports.infrastructure.persistence.entity;

import com.transformersas.marketplace.reports.domain.model.NotificationChannel;
import com.transformersas.marketplace.reports.domain.model.NotificationStatus;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Notificación interna (ya entregada) o elemento del outbox hacia el servicio externo. */
@Entity
@Table(name = "moderation_notifications")
@Getter
@Setter
@NoArgsConstructor
public class ModerationNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(nullable = false, length = 100)
    private String template;

    @Column(columnDefinition = "json", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    private LocalDateTime nextAttemptAt;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;
}
