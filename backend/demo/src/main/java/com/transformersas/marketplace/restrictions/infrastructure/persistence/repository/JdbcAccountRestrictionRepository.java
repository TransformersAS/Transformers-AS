package com.transformersas.marketplace.restrictions.infrastructure.persistence.repository;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.restrictions.domain.model.AccountRestriction;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionChange;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionScope;
import com.transformersas.marketplace.restrictions.domain.model.RestrictionStatus;
import com.transformersas.marketplace.restrictions.domain.repository.AccountRestrictionRepository;
import com.transformersas.marketplace.users.domain.model.Role;

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

/**
 * Restricciones y su historia. role guarda el nombre del rol afectado, o el valor fijo "ACCOUNT" cuando la
 * restricción es sobre toda la cuenta; así la unicidad por alcance vigente (uq_account_restrictions_active) cubre
 * los dos casos con una sola columna. Los cambios son compare-and-set sobre la versión y el estado ACTIVE.
 */
@Repository
public class JdbcAccountRestrictionRepository implements AccountRestrictionRepository {

    private static final String ACCOUNT_SCOPE_ROLE = "ACCOUNT";

    private static final String SELECT = """
            SELECT id, account_id, scope, role, reason, permanent, starts_at, ends_at, status,
                   applied_by_account_id, closed_at, closed_by_account_id, close_reason, version
            FROM account_restrictions""";

    private final JdbcClient jdbc;

    public JdbcAccountRestrictionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Long insert(AccountRestriction restriction) {
        var keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                            INSERT INTO account_restrictions (account_id, scope, role, reason, permanent, starts_at,
                                                              ends_at, status, applied_by_account_id, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                    .params(restriction.accountId(), restriction.scope().name(), roleColumn(restriction),
                            restriction.reason(), restriction.permanent(), restriction.startsAt(),
                            restriction.endsAt(), restriction.appliedBy(), restriction.startsAt())
                    .update(keys);
        } catch (DuplicateKeyException alreadyActive) {
            throw BusinessException.conflict("ACCOUNT_ALREADY_RESTRICTED",
                    "La cuenta ya tiene una restricción vigente con ese alcance; modifícala o retírala");
        }
        return keys.getKey().longValue();
    }

    @Override
    public Optional<AccountRestriction> findById(Long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::toRestriction).optional();
    }

    @Override
    public List<AccountRestriction> findActiveByAccount(Long accountId) {
        return jdbc.sql(SELECT + " WHERE account_id = ? AND status = 'ACTIVE' ORDER BY starts_at DESC, id DESC")
                .param(accountId).query(this::toRestriction).list();
    }

    @Override
    public List<AccountRestriction> findByAccount(Long accountId) {
        return jdbc.sql(SELECT + " WHERE account_id = ? ORDER BY starts_at DESC, id DESC").param(accountId)
                .query(this::toRestriction).list();
    }

    @Override
    public List<AccountRestriction> findDue(LocalDateTime now, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE status = 'ACTIVE' AND permanent = FALSE AND ends_at <= ?
                         ORDER BY ends_at, id LIMIT ?""")
                .params(now, limit).query(this::toRestriction).list();
    }

    @Override
    public boolean hasActiveRestriction(Long accountId, Role role) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM account_restrictions
                        WHERE account_id = ? AND status = 'ACTIVE' AND role IN (?, ?)""")
                .params(accountId, ACCOUNT_SCOPE_ROLE, role.name())
                .query(Integer.class).single() > 0;
    }

    @Override
    public boolean changeEnd(Long id, long version, LocalDateTime newEndsAt) {
        return jdbc.sql("""
                        UPDATE account_restrictions SET ends_at = ?, version = version + 1
                        WHERE id = ? AND version = ? AND status = 'ACTIVE' AND permanent = FALSE""")
                .params(newEndsAt, id, version).update() == 1;
    }

    @Override
    public boolean makePermanent(Long id, long version) {
        return jdbc.sql("""
                        UPDATE account_restrictions SET permanent = TRUE, ends_at = NULL, version = version + 1
                        WHERE id = ? AND version = ? AND status = 'ACTIVE'""")
                .params(id, version).update() == 1;
    }

    @Override
    public boolean makeTemporary(Long id, long version, LocalDateTime newEndsAt) {
        return jdbc.sql("""
                        UPDATE account_restrictions SET permanent = FALSE, ends_at = ?, version = version + 1
                        WHERE id = ? AND version = ? AND status = 'ACTIVE'""")
                .params(newEndsAt, id, version).update() == 1;
    }

    @Override
    public boolean close(Long id, long version, RestrictionStatus status, LocalDateTime closedAt, Long closedBy,
                         String closeReason) {
        return jdbc.sql("""
                        UPDATE account_restrictions
                        SET status = ?, active_key = NULL, closed_at = ?, closed_by_account_id = ?,
                            close_reason = ?, version = version + 1
                        WHERE id = ? AND version = ? AND status = 'ACTIVE'""")
                .params(status.name(), closedAt, closedBy, closeReason, id, version).update() == 1;
    }

    @Override
    public Long addChange(RestrictionChange change) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO account_restriction_changes (restriction_id, change_type, previous_ends_at,
                                                                 new_ends_at, justification, actor_account_id,
                                                                 created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(change.restrictionId(), change.type().name(), change.previousEndsAt(), change.newEndsAt(),
                        change.justification(), change.actorId(), change.createdAt())
                .update(keys);
        return keys.getKey().longValue();
    }

    @Override
    public Map<Long, List<RestrictionChange>> findChanges(List<Long> restrictionIds) {
        if (restrictionIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT id, restriction_id, change_type, previous_ends_at, new_ends_at, justification,
                               actor_account_id, created_at
                        FROM account_restriction_changes WHERE restriction_id IN (:ids) ORDER BY id""")
                .param("ids", restrictionIds)
                .query((rs, row) -> new RestrictionChange(rs.getLong("id"), rs.getLong("restriction_id"),
                        RestrictionChange.Type.valueOf(rs.getString("change_type")),
                        rs.getObject("previous_ends_at", LocalDateTime.class),
                        rs.getObject("new_ends_at", LocalDateTime.class), rs.getString("justification"),
                        rs.getObject("actor_account_id", Long.class),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list().stream()
                .collect(Collectors.groupingBy(RestrictionChange::restrictionId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    private static String roleColumn(AccountRestriction restriction) {
        return restriction.role() == null ? ACCOUNT_SCOPE_ROLE : restriction.role().name();
    }

    private AccountRestriction toRestriction(ResultSet rs, int row) throws SQLException {
        String roleColumn = rs.getString("role");
        Role role = ACCOUNT_SCOPE_ROLE.equals(roleColumn) ? null : Role.valueOf(roleColumn);
        return new AccountRestriction(rs.getLong("id"), rs.getLong("account_id"),
                RestrictionScope.valueOf(rs.getString("scope")), role, rs.getString("reason"),
                rs.getBoolean("permanent"), rs.getObject("starts_at", LocalDateTime.class),
                rs.getObject("ends_at", LocalDateTime.class), RestrictionStatus.valueOf(rs.getString("status")),
                rs.getLong("applied_by_account_id"), rs.getObject("closed_at", LocalDateTime.class),
                rs.getObject("closed_by_account_id", Long.class), rs.getString("close_reason"),
                rs.getLong("version"));
    }
}
