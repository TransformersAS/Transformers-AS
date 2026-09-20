package com.transformersas.marketplace.reports.infrastructure.persistence.entity;

import com.transformersas.marketplace.reports.domain.model.ContentModerationState;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@IdClass(ContentModerationStateId.class)
@Table(name = "content_moderation_state")
@Getter
@Setter
@NoArgsConstructor
public class ContentModerationStateEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 100)
    private ReportContentType contentType;

    @Id
    private String contentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ContentModerationState state;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
