package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.domain.model.IssueStatus;
import com.transformersas.marketplace.orders.domain.model.IssueType;
import com.transformersas.marketplace.orders.domain.model.OrderIssue;
import com.transformersas.marketplace.orders.domain.repository.OrderIssueRepository;
import com.transformersas.marketplace.shared.audit.ActorType;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Adaptador JDBC de novedades. La unicidad de la inconsistencia abierta la garantiza un índice UNIQUE (V14). */
@Repository
public class JdbcOrderIssueRepository implements OrderIssueRepository {

    private static final String COLUMNS = """
            id, order_id, type, description, status, reported_by_type, reported_by_id, created_at, resolved_at,
            resolved_by_id""";

    private final JdbcClient jdbc;

    public JdbcOrderIssueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Registration register(OrderIssue issue) {
        try {
            jdbc.sql("""
                            INSERT INTO order_issues (order_id, type, description, status, reported_by_type,
                                                      reported_by_id, created_at)
                            VALUES (?, ?, ?, 'OPEN', ?, ?, ?)""")
                    .params(issue.orderId(), issue.type().name(), issue.description(), issue.reportedByType().name(),
                            issue.reportedById(), issue.createdAt())
                    .update();
            Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
            return new Registration(findById(id).orElseThrow(), true);
        } catch (DuplicateKeyException duplicate) {
            // Ya había una inconsistencia de inventario abierta para este pedido (carrera o repetición).
            OrderIssue existing = findOpenByOrderId(issue.orderId()).stream()
                    .filter(open -> open.type() == IssueType.INVENTORY_INCONSISTENCY).findFirst()
                    .orElseThrow(() -> duplicate);
            return new Registration(existing, false);
        }
    }

    @Override
    public Optional<OrderIssue> findByIdAndOrderId(Long issueId, Long orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM order_issues WHERE id = ? AND order_id = ?")
                .params(issueId, orderId).query((rs, row) -> map(rs)).optional();
    }

    @Override
    public List<OrderIssue> findByOrderId(Long orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM order_issues WHERE order_id = ? ORDER BY id")
                .param(orderId).query((rs, row) -> map(rs)).list();
    }

    @Override
    public List<OrderIssue> findOpenByOrderId(Long orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM order_issues WHERE order_id = ? AND status = 'OPEN' ORDER BY id")
                .param(orderId).query((rs, row) -> map(rs)).list();
    }

    @Override
    public boolean resolve(Long issueId, Long resolvedById, LocalDateTime resolvedAt) {
        return jdbc.sql("""
                        UPDATE order_issues SET status = 'RESOLVED', resolved_at = ?, resolved_by_id = ?
                        WHERE id = ? AND status = 'OPEN'""")
                .params(resolvedAt, resolvedById, issueId).update() == 1;
    }

    private Optional<OrderIssue> findById(Long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM order_issues WHERE id = ?").param(id)
                .query((rs, row) -> map(rs)).optional();
    }

    private static OrderIssue map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderIssue(rs.getLong("id"), rs.getLong("order_id"), IssueType.valueOf(rs.getString("type")),
                rs.getString("description"), IssueStatus.valueOf(rs.getString("status")),
                ActorType.valueOf(rs.getString("reported_by_type")), rs.getObject("reported_by_id", Long.class),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("resolved_at", LocalDateTime.class),
                rs.getObject("resolved_by_id", Long.class));
    }
}
