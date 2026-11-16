package com.transformersas.marketplace.restrictions.infrastructure.persistence.repository;

import com.transformersas.marketplace.restrictions.domain.model.AccountRecord;
import com.transformersas.marketplace.restrictions.domain.port.StoreOperationalLink;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRecordQuery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lee, sin modificarlas, las decisiones que tomaron otros casos de uso contra la tienda de la cuenta: reclamaciones
 * (CU-13) resueltas por soporte con reembolso al comprador y decisiones de moderación (CU-21) que ocultaron o
 * retiraron una publicación de la tienda o la tienda misma (mismo criterio de confirmación que CU-27).
 */
@Repository
class JdbcAccountRecordQuery implements AccountRecordQuery {

    private static final String CONFIRMED_CLAIMS = "status = 'RESOLVED' AND resolution = 'REFUND_GRANTED'";

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

    private final JdbcClient jdbc;
    private final StoreOperationalLink storeLink;

    JdbcAccountRecordQuery(JdbcClient jdbc, StoreOperationalLink storeLink) {
        this.jdbc = jdbc;
        this.storeLink = storeLink;
    }

    @Override
    public List<AccountRecord> confirmedStoreRecords(Long accountId) {
        return storeLink.storeOwnedBy(accountId).map(this::confirmedRecords).orElseGet(List::of);
    }

    private List<AccountRecord> confirmedRecords(Long storeId) {
        List<AccountRecord> claims = jdbc.sql("""
                        SELECT id, order_id, product_name, resolution_note, refund_amount, updated_at
                        FROM claims WHERE store_id = ? AND %s""".formatted(CONFIRMED_CLAIMS))
                .param(storeId)
                .query((rs, row) -> new AccountRecord(AccountRecord.Type.CLAIM, rs.getLong("id"),
                        rs.getObject("updated_at", LocalDateTime.class),
                        rs.getString("product_name") + " (pedido #" + rs.getLong("order_id") + ")",
                        "REFUND_GRANTED", rs.getString("resolution_note"), rs.getBigDecimal("refund_amount")))
                .list();
        List<AccountRecord> moderation = jdbc.sql("SELECT * FROM (" + CONFIRMED_MODERATION + ") m WHERE store_id = ?")
                .param(storeId)
                .query((rs, row) -> new AccountRecord(AccountRecord.Type.MODERATION, rs.getLong("case_id"),
                        rs.getObject("created_at", LocalDateTime.class),
                        "TIENDA".equals(rs.getString("content_type")) ? "La tienda"
                                : "Publicación: " + rs.getString("product_name"),
                        rs.getString("decision"), rs.getString("justification"), null))
                .list();
        return Stream.concat(claims.stream(), moderation.stream())
                .sorted(Comparator.comparing(AccountRecord::decidedAt).reversed())
                .toList();
    }
}
