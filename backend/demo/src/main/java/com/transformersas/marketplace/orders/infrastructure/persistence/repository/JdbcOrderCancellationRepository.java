package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.domain.model.CancellationInitiator;
import com.transformersas.marketplace.orders.domain.model.CancellationReason;
import com.transformersas.marketplace.orders.domain.model.OrderCancellation;
import com.transformersas.marketplace.orders.domain.repository.OrderCancellationRepository;
import com.transformersas.marketplace.shared.error.BusinessException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/** Adaptador JDBC de cancelaciones. UNIQUE(order_id) impide dos cancelaciones del mismo pedido. */
@Repository
public class JdbcOrderCancellationRepository implements OrderCancellationRepository {

    private final JdbcClient jdbc;

    public JdbcOrderCancellationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(OrderCancellation cancellation) {
        try {
            jdbc.sql("""
                            INSERT INTO order_cancellations (order_id, initiator, reason_code, details, cancelled_by_id,
                                                             correlation_id, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""")
                    .params(cancellation.orderId(), cancellation.initiator().name(), cancellation.reason().name(),
                            cancellation.details(), cancellation.cancelledById(), cancellation.correlationId(),
                            cancellation.createdAt())
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.conflict("ORDER_ALREADY_CANCELLED", "El pedido ya fue cancelado");
        }
    }

    @Override
    public Optional<OrderCancellation> findByOrderId(Long orderId) {
        return jdbc.sql("""
                        SELECT id, order_id, initiator, reason_code, details, cancelled_by_id, correlation_id, created_at
                        FROM order_cancellations WHERE order_id = ?""")
                .param(orderId)
                .query((rs, row) -> new OrderCancellation(rs.getLong("id"), rs.getLong("order_id"),
                        CancellationInitiator.valueOf(rs.getString("initiator")),
                        CancellationReason.valueOf(rs.getString("reason_code")), rs.getString("details"),
                        rs.getObject("cancelled_by_id", Long.class), rs.getString("correlation_id"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .optional();
    }
}
