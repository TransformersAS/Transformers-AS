package com.transformersas.marketplace.reports.infrastructure.persistence.entity;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Reporte individual (CU-20). Es inmutable: conserva quién reportó y por qué, agrupado en un caso. */
@Entity
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
public class ReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private String reporterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private ReportContentType contentType;

    @Column(nullable = false)
    private String contentId;

    @Column(nullable = false)
    private String reason;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
