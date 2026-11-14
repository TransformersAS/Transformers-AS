package com.transformersas.marketplace.suspensions.infrastructure.persistence.repository;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.suspensions.domain.model.StoreSuspension;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionChange;
import com.transformersas.marketplace.suspensions.domain.model.SuspensionStatus;
import com.transformersas.marketplace.suspensions.domain.repository.StoreSuspensionRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Suspensiones y su historia. Los cambios son compare-and-set sobre la versión y el estado ACTIVE. */
@Repository
public class JdbcStoreSuspensionRepository implements StoreSuspensionRepository {

    private static final String SELECT = """
            SELECT id, store_id, reason, starts_at, ends_at, status, applied_by_account_id, closed_at,
                   closed_by_account_id, close_reason, version
            FROM store_suspensions""";

    private final JdbcClient jdbc;

    public JdbcStoreSuspensionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Long insert(StoreSuspension suspension) {
        var keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                            INSERT INTO store_suspensions (store_id, reason, starts_at, ends_at, status,
                                                           applied_by_account_id, created_at)
                            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                    .params(suspension.storeId(), suspension.reason(), suspension.startsAt(), suspension.endsAt(),
                            suspension.appliedBy(), suspension.startsAt())
                    .update(keys);
        } catch (DuplicateKeyException alreadyActive) {
            throw BusinessException.conflict("STORE_ALREADY_SUSPENDED",
                    "La tienda ya tiene una suspensión vigente; modifícala o finalízala");
        }
        return keys.getKey().longValue();
    }

    @Override
    public Optional<StoreSuspension> findById(Long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::toSuspension).optional();
    }

    @Override
    public Optional<StoreSuspension> findActiveByStore(Long storeId) {
        return jdbc.sql(SELECT + " WHERE store_id = ? AND status = 'ACTIVE'").param(storeId)
                .query(this::toSuspension).optional();
    }

    @Override
    public List<StoreSuspension> findByStore(Long storeId) {
        return jdbc.sql(SELECT + " WHERE store_id = ? ORDER BY starts_at DESC, id DESC").param(storeId)
                .query(this::toSuspension).list();
    }

    @Override
    public List<StoreSuspension> findDue(LocalDateTime now, int limit) {
        return jdbc.sql(SELECT + " WHERE status = 'ACTIVE' AND ends_at <= ? ORDER BY ends_at, id LIMIT ?")
                .params(now, limit).query(this::toSuspension).list();
    }

    @Override
    public boolean changeEnd(Long id, long version, LocalDateTime newEndsAt) {
        return jdbc.sql("""
                        UPDATE store_suspensions SET ends_at = ?, version = version + 1
                        WHERE id = ? AND version = ? AND status = 'ACTIVE'""")
                .params(newEndsAt, id, version).update() == 1;
    }

    @Override
    public boolean close(Long id, long version, SuspensionStatus status, LocalDateTime closedAt, Long closedBy,
                         String closeReason) {
        return jdbc.sql("""
                        UPDATE store_suspensions
                        SET status = ?, active_key = NULL, closed_at = ?, closed_by_account_id = ?, close_reason = ?,
                            version = version + 1
                        WHERE id = ? AND version = ? AND status = 'ACTIVE'""")
                .params(status.name(), closedAt, closedBy, closeReason, id, version).update() == 1;
    }

    @Override
    public Long addChange(SuspensionChange change) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO store_suspension_changes (suspension_id, change_type, previous_ends_at, new_ends_at,
                                                              justification, actor_account_id, reactivation_blocked,
                                                              created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(change.suspensionId(), change.type().name(), change.previousEndsAt(), change.newEndsAt(),
                        change.justification(), change.actorId(), change.reactivationBlocked(), change.createdAt())
                .update(keys);
        return keys.getKey().longValue();
    }

    @Override
    public Map<Long, List<SuspensionChange>> findChanges(List<Long> suspensionIds) {
        if (suspensionIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT id, suspension_id, change_type, previous_ends_at, new_ends_at, justification,
                               actor_account_id, reactivation_blocked, created_at
                        FROM store_suspension_changes WHERE suspension_id IN (:ids) ORDER BY id""")
                .param("ids", suspensionIds)
                .query((rs, row) -> new SuspensionChange(rs.getLong("id"), rs.getLong("suspension_id"),
                        SuspensionChange.Type.valueOf(rs.getString("change_type")),
                        rs.getObject("previous_ends_at", LocalDateTime.class),
                        rs.getObject("new_ends_at", LocalDateTime.class), rs.getString("justification"),
                        rs.getObject("actor_account_id", Long.class), rs.getBoolean("reactivation_blocked"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list().stream()
                .collect(Collectors.groupingBy(SuspensionChange::suspensionId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    private StoreSuspension toSuspension(ResultSet rs, int row) throws SQLException {
        return new StoreSuspension(rs.getLong("id"), rs.getLong("store_id"), rs.getString("reason"),
                rs.getObject("starts_at", LocalDateTime.class), rs.getObject("ends_at", LocalDateTime.class),
                SuspensionStatus.valueOf(rs.getString("status")), rs.getLong("applied_by_account_id"),
                rs.getObject("closed_at", LocalDateTime.class), rs.getObject("closed_by_account_id", Long.class),
                rs.getString("close_reason"), rs.getLong("version"));
    }
}
