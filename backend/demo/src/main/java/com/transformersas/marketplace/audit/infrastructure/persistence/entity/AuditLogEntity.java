package com.transformersas.marketplace.audit.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/** Registro de auditoría: Hibernate ignora cualquier intento de modificarlo tras insertarlo. */
@Entity
@Immutable
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String actorId;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false, length = 100)
    private String entityType;

    @Column(nullable = false)
    private String entityId;

    @Column(nullable = false, length = 100)
    private String result;

    @Column(columnDefinition = "json")
    private String metadata;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
