package com.transformersas.marketplace.suspensions.infrastructure.persistence.repository;

import com.transformersas.marketplace.suspensions.domain.model.PendingOperations;
import com.transformersas.marketplace.suspensions.domain.model.StoreRecord;
import com.transformersas.marketplace.suspensions.domain.model.StoreRecordSummary;
import com.transformersas.marketplace.suspensions.domain.repository.StoreRecordQuery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Modelo de lectura de antecedentes. Lee, sin modificarlas, las decisiones que tomaron otros casos de uso:
 * reclamaciones (CU-13) resueltas por soporte con reembolso al comprador y decisiones de moderación (CU-21) que
 * ocultaron o retiraron una publicación de la tienda o la tienda misma. MANTENER no es un incumplimiento y una
 * reclamación o un reporte abiertos tampoco (RF-168).
 */
@Repository
public class JdbcStoreRecordQuery implements StoreRecordQuery {

    /** Reclamaciones en las que soporte determinó la responsabilidad del vendedor. */
    private static final String CONFIRMED_CLAIMS = "status = 'RESOLVED' AND resolution = 'REFUND_GRANTED'";

    /** Decisiones que confirmaron un incumplimiento, con la tienda a la que pertenece el contenido. */
    private static final String CONFIRMED_MODERATION = """
            SELECT a.id AS action_id, a.case_id, a.decision, a.justification, a.created_at,
                   mc.content_type, p.name AS product_name,
                   CASE WHEN mc.content_type = 'TIENDA' THEN CAST(mc.content_id AS UNSIGNED) ELSE p.store_id END
                       AS store_id
            FROM moderation_actions a
            JOIN moderation_cases mc ON mc.id = a.case_id
            LEFT JOIN products p ON mc.content_type = 'PUBLICACION' AND p.id = CAST(mc.content_id AS UNSIGNED)
            WHERE a.decision IN ('OCULTAR_TEMPORALMENTE', 'RETIRAR')
              AND (mc.content_type = 'TIENDA' OR p.id IS NOT NULL)""";

    /** Primero las suspendidas, luego las de más antecedentes recientes y luego las de más antecedentes en total. */
    private static final Comparator<StoreRecordSummary> MOST_COMPROMISED_FIRST =
            Comparator.comparing(StoreRecordSummary::activeSuspension).reversed()
                    .thenComparing(Comparator.comparingLong(StoreRecordSummary::recentRecords).reversed())
                    .thenComparing(Comparator.comparingLong(
                            (StoreRecordSummary s) -> s.claims() + s.moderations() + s.suspensions()).reversed())
                    .thenComparing(StoreRecordSummary::storeId);

    private final JdbcClient jdbc;

    public JdbcStoreRecordQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StoreRecordSummary> storesWithRecords(LocalDateTime recentSince) {
        // Un caso de moderación cuenta una vez aunque tenga varias decisiones (ocultar y luego retirar).
        return jdbc.sql("""
                        SELECT s.id, s.name, s.status,
                               COALESCE(cl.total, 0) AS claims, COALESCE(md.total, 0) AS moderations,
                               COALESCE(su.total, 0) AS suspensions,
                               COALESCE(cl.recent, 0) + COALESCE(md.recent, 0) AS recent_records,
                               cl.last_at AS last_claim_at, md.last_at AS last_moderation_at,
                               COALESCE(su.active, 0) > 0 AS active_suspension,
                               COALESCE(cr.open_requests, 0) > 0 AS open_clarification
                        FROM stores s
                        LEFT JOIN (SELECT store_id, COUNT(*) AS total, SUM(updated_at >= :since) AS recent,
                                          MAX(updated_at) AS last_at
                                   FROM claims WHERE %s GROUP BY store_id) cl ON cl.store_id = s.id
                        LEFT JOIN (SELECT store_id, COUNT(DISTINCT case_id) AS total,
                                          COUNT(DISTINCT CASE WHEN created_at >= :since THEN case_id END) AS recent,
                                          MAX(created_at) AS last_at
                                   FROM (%s) m GROUP BY store_id) md ON md.store_id = s.id
                        LEFT JOIN (SELECT store_id, COUNT(*) AS total, SUM(status = 'ACTIVE') AS active
                                   FROM store_suspensions GROUP BY store_id) su ON su.store_id = s.id
                        LEFT JOIN (SELECT store_id, COUNT(*) AS open_requests FROM store_clarification_requests
                                   WHERE status = 'OPEN' GROUP BY store_id) cr ON cr.store_id = s.id
                        WHERE COALESCE(cl.total, 0) + COALESCE(md.total, 0) + COALESCE(su.total, 0) > 0"""
                        .formatted(CONFIRMED_CLAIMS, CONFIRMED_MODERATION))
                .param("since", recentSince)
                .query((rs, row) -> new StoreRecordSummary(rs.getLong("id"), rs.getString("name"),
                        rs.getString("status"), rs.getLong("claims"), rs.getLong("moderations"),
                        rs.getLong("suspensions"), rs.getLong("recent_records"),
                        latest(rs.getObject("last_claim_at", LocalDateTime.class),
                                rs.getObject("last_moderation_at", LocalDateTime.class)),
                        rs.getBoolean("active_suspension"), rs.getBoolean("open_clarification")))
                .list().stream()
                .sorted(MOST_COMPROMISED_FIRST)
                .toList();
    }

    private static LocalDateTime latest(LocalDateTime first, LocalDateTime second) {
        if (first == null) {
            return second;
        }
        return second == null || first.isAfter(second) ? first : second;
    }

    @Override
    public List<StoreRecord> confirmedRecords(Long storeId) {
        List<StoreRecord> claims = jdbc.sql("""
                        SELECT id, order_id, product_name, resolution_note, refund_amount, updated_at
                        FROM claims WHERE store_id = ? AND %s""".formatted(CONFIRMED_CLAIMS))
                .param(storeId)
                .query((rs, row) -> new StoreRecord(StoreRecord.Type.CLAIM, rs.getLong("id"),
                        rs.getObject("updated_at", LocalDateTime.class),
                        rs.getString("product_name") + " (pedido #" + rs.getLong("order_id") + ")", "REFUND_GRANTED",
                        rs.getString("resolution_note"), rs.getBigDecimal("refund_amount")))
                .list();
        List<StoreRecord> moderation = jdbc.sql("SELECT * FROM (" + CONFIRMED_MODERATION + ") m WHERE store_id = ?")
                .param(storeId)
                .query((rs, row) -> new StoreRecord(StoreRecord.Type.MODERATION, rs.getLong("case_id"),
                        rs.getObject("created_at", LocalDateTime.class),
                        "TIENDA".equals(rs.getString("content_type")) ? "La tienda"
                                : "Publicación: " + rs.getString("product_name"),
                        rs.getString("decision"), rs.getString("justification"), null))
                .list();
        return Stream.concat(claims.stream(), moderation.stream())
                .sorted(Comparator.comparing(StoreRecord::decidedAt).reversed())
                .toList();
    }

    @Override
    public PendingOperations pendingOperations(Long storeId) {
        return jdbc.sql("""
                        SELECT (SELECT COUNT(*) FROM orders WHERE store_id = :store
                                  AND status IN ('CONFIRMED', 'IN_PREPARATION', 'READY_FOR_DISPATCH', 'PICKED_UP',
                                                 'IN_TRANSIT', 'DELIVERY_EXCEPTION', 'DELIVERY_ATTEMPT_FAILED',
                                                 'CANCELLATION_REQUESTED')) AS orders,
                               (SELECT COUNT(*) FROM claims WHERE store_id = :store AND status <> 'RESOLVED') AS claims,
                               (SELECT COUNT(*) FROM return_requests WHERE store_id = :store
                                  AND status NOT IN ('REJECTED', 'FINISHED')) AS returns_open""")
                .param("store", storeId)
                .query((rs, row) -> new PendingOperations(rs.getLong("orders"), rs.getLong("claims"),
                        rs.getLong("returns_open")))
                .single();
    }
}
