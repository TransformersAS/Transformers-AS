package com.transformersas.marketplace.reports.infrastructure.persistence.entity;

import com.transformersas.marketplace.reports.domain.model.MeasureResult;
import com.transformersas.marketplace.reports.domain.model.ModerationDecision;
import com.transformersas.marketplace.reports.domain.model.ReportStatus;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/** Decisión de moderación registrada sobre un caso (RF-159). Inmutable una vez creada. */
@Entity
@Immutable
@Table(name = "moderation_actions")
@Getter
@Setter
@NoArgsConstructor
public class ModerationActionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private String agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private ModerationDecision decision;

    @Column(columnDefinition = "text", nullable = false)
    private String justification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MeasureResult measureResult;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReportStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReportStatus newStatus;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
