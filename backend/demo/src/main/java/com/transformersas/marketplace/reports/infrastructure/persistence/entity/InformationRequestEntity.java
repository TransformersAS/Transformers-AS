package com.transformersas.marketplace.reports.infrastructure.persistence.entity;

import com.transformersas.marketplace.reports.domain.model.InfoRequestStatus;
import com.transformersas.marketplace.reports.domain.model.InfoRequestTarget;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "information_requests")
@Getter
@Setter
@NoArgsConstructor
public class InformationRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_role", nullable = false, length = 30)
    private InfoRequestTarget target;

    @Column(nullable = false)
    private String targetUserId;

    @Column(columnDefinition = "text", nullable = false)
    private String message;

    @Column(nullable = false)
    private String requestedBy;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    @Column(nullable = false)
    private LocalDateTime dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InfoRequestStatus status;

    private LocalDateTime respondedAt;

    @Column(columnDefinition = "text")
    private String responseText;
}
