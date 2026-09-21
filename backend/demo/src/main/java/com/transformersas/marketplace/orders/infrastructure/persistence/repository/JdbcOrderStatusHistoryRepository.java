package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.model.OrderStatusHistoryEntry;
import com.transformersas.marketplace.orders.domain.repository.OrderStatusHistoryRepository;
import com.transformersas.marketplace.shared.audit.ActorType;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Adaptador JDBC del historial: tabla append-only sin entidad JPA. */
@Repository
public class JdbcOrderStatusHistoryRepository implements OrderStatusHistoryRepository {

    private final JdbcClient jdbc;

    public JdbcOrderStatusHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(OrderStatusHistoryEntry entry) {
        jdbc.sql("""
                        INSERT INTO order_status_history (order_id, from_status, to_status, actor_type, actor_id,
                                                          reason, correlation_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(entry.orderId(), entry.fromStatus() == null ? null : entry.fromStatus().name(),
                        entry.toStatus().name(), entry.actorType().name(), entry.actorId(), entry.reason(),
                        entry.correlationId(), entry.createdAt())
                .update();
    }

    @Override
    public List<OrderStatusHistoryEntry> findByOrderId(Long orderId) {
        return jdbc.sql("""
                        SELECT id, order_id, from_status, to_status, actor_type, actor_id, reason, correlation_id,
                               created_at
                        FROM order_status_history WHERE order_id = ? ORDER BY id""")
                .param(orderId)
                .query((rs, row) -> new OrderStatusHistoryEntry(
                        rs.getLong("id"),
                        rs.getLong("order_id"),
                        rs.getString("from_status") == null ? null : OrderStatus.valueOf(rs.getString("from_status")),
                        OrderStatus.valueOf(rs.getString("to_status")),
                        ActorType.valueOf(rs.getString("actor_type")),
                        rs.getObject("actor_id", Long.class),
                        rs.getString("reason"),
                        rs.getString("correlation_id"),
                        rs.getObject("created_at", java.time.LocalDateTime.class)))
                .list();
    }
}
