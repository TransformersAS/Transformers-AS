package com.transformersas.marketplace.reports.infrastructure.persistence.entity;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.model.ReportStatus;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * Caso de moderación: agrupa todos los reportes sobre un mismo contenido (RF-156) y concentra las
 * transiciones de estado. {@code reportCount} lo incrementa la base de datos al radicar reportes,
 * por eso la entidad solo escribe las columnas que cambia ({@link DynamicUpdate}).
 */
@Entity
@DynamicUpdate
@Table(name = "moderation_cases")
@Getter
@NoArgsConstructor
public class ModerationCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100, updatable = false)
    private ReportContentType contentType;

    @Column(nullable = false, updatable = false)
    private String contentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReportStatus status;

    private String assignedAgentId;

    @Column(nullable = false)
    private int reportCount;

    /** 1 mientras está abierto; NULL al resolverse (garantiza un caso abierto por contenido). */
    private Integer openKey;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private LocalDateTime openedAt;

    private LocalDateTime resolvedAt;

    public boolean isOpen() {
        return status != ReportStatus.RESUELTO;
    }

    /**
     * Deja el caso listo para que {@code agentId} trabaje sobre él: lo toma si está sin asignar y
     * rechaza a otro agente o un caso ya resuelto.
     */
    public void ensureWorkableBy(String agentId) {
        if (!isOpen()) {
            throw conflict("El caso ya está resuelto.");
        }
        if (assignedAgentId != null && !assignedAgentId.equals(agentId)) {
            throw conflict("El caso está asignado a otro agente.");
        }
        if (status == ReportStatus.PENDIENTE) {
            status = ReportStatus.EN_REVISION;
        }
        assignedAgentId = agentId;
    }

    /** Pasa a esperar información; permitido desde revisión o si ya se espera otra respuesta. */
    public void markInformationRequested() {
        if (status != ReportStatus.EN_REVISION && status != ReportStatus.INFO_SOLICITADA) {
            throw conflict("El caso debe estar en revisión para solicitar información.");
        }
        status = ReportStatus.INFO_SOLICITADA;
    }

    /** Vuelve a revisión cuando ya no quedan solicitudes abiertas (respondidas o vencidas). */
    public void resumeReview() {
        if (status == ReportStatus.INFO_SOLICITADA) {
            status = ReportStatus.EN_REVISION;
        }
    }

    public void resolve(LocalDateTime now) {
        if (!isOpen()) {
            throw conflict("El caso ya está resuelto.");
        }
        status = ReportStatus.RESUELTO;
        openKey = null;
        resolvedAt = now;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
