package com.transformersas.marketplace.reports.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Remisión a CU-22 (administración de cuentas). Solo registra la solicitud; no toca cuentas. */
@Entity
@Table(name = "moderation_referrals")
@Getter
@Setter
@NoArgsConstructor
public class ModerationReferralEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private String agentId;

    @Column(columnDefinition = "text", nullable = false)
    private String justification;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
