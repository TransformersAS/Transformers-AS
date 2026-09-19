package com.transformersas.marketplace.reports.infrastructure.persistence.repository;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.domain.model.ReportStatus;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationCaseEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ModerationCaseRepository extends JpaRepository<ModerationCaseEntity, Long> {

    Optional<ModerationCaseEntity> findByContentTypeAndContentIdAndOpenKey(
            ReportContentType contentType, String contentId, Integer openKey);

    List<ModerationCaseEntity> findByContentTypeAndContentIdAndStatusOrderByResolvedAtDesc(
            ReportContentType contentType, String contentId, ReportStatus status);

    @Query("select c from ModerationCaseEntity c where c.status in :statuses and c.contentType in :types")
    Page<ModerationCaseEntity> search(@Param("statuses") Collection<ReportStatus> statuses,
                                      @Param("types") Collection<ReportContentType> types,
                                      Pageable pageable);

    /** Serializa a los agentes que operan sobre el mismo caso. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ModerationCaseEntity c where c.id = :id")
    Optional<ModerationCaseEntity> lockById(@Param("id") Long id);

    /**
     * Abre el caso del contenido o suma un reporte al que ya está abierto, de forma atómica: la
     * restricción única (tipo, contenido, open_key) hace que dos reportes concurrentes terminen en
     * el mismo caso.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO moderation_cases
                (content_type, content_id, status, report_count, open_key, version, opened_at)
            VALUES (:type, :contentId, 'PENDIENTE', 1, 1, 0, :now)
            ON DUPLICATE KEY UPDATE report_count = report_count + 1
            """, nativeQuery = true)
    void openOrAddReport(@Param("type") String type, @Param("contentId") String contentId,
                         @Param("now") LocalDateTime now);
}
