package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.returns.domain.model.ReturnLine;
import com.transformersas.marketplace.returns.domain.port.OrderForReturn;
import com.transformersas.marketplace.returns.domain.port.OrderForReturn.Pickup;
import com.transformersas.marketplace.returns.domain.port.OrderForReturnReader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lectura de pedidos para devoluciones (CU-19). Lee solo lo que hace falta y solo de pedidos del comprador: un pedido
 * ajeno responde igual que uno inexistente.
 *
 * <p>La fecha de entrega no está en orders. Se toma de la primera entrega que aplicó el seguimiento logístico
 * (shipment_tracking_events: DELIVERED con resultado APPLIED, por occurred_at, que es la hora real del proveedor) y, si
 * no hay, de la primera vez que el pedido pasó a DELIVERED en order_status_history (hora de registro). Si el pedido
 * consta como entregado y ninguna de las dos existe, la fecha queda nula y devoluciones no inventa una.
 */
@Repository
class JdbcOrderForReturnReader implements OrderForReturnReader {
    private final JdbcClient jdbc;

    JdbcOrderForReturnReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record OrderRow(Long id, Long accountId, Long storeId, boolean delivered, Pickup pickup) {
    }

    private static final String ORDER_COLUMNS = "id, account_id, store_id, status, delivery_recipient_name, "
            + "delivery_street, delivery_city, delivery_department, delivery_postal_code, delivery_phone";

    private static OrderRow orderRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String street = rs.getString("delivery_street");
        Pickup pickup = street == null ? null : new Pickup(rs.getString("delivery_recipient_name"), street,
                rs.getString("delivery_city"), rs.getString("delivery_department"),
                rs.getString("delivery_postal_code"), rs.getString("delivery_phone"));
        return new OrderRow(rs.getLong("id"), rs.getLong("account_id"), rs.getLong("store_id"),
                "DELIVERED".equals(rs.getString("status")), pickup);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderForReturn> findForBuyer(Long orderId, Long buyerAccountId) {
        Optional<OrderRow> row = jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM orders WHERE id = ? "
                        + "AND account_id = ?").params(orderId, buyerAccountId)
                .query((rs, n) -> orderRow(rs)).optional();
        return row.map(order -> assemble(List.of(order)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderForReturn> findByBuyer(Long buyerAccountId) {
        List<OrderRow> rows = jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM orders WHERE account_id = ? "
                        + "ORDER BY created_at DESC, id DESC").param(buyerAccountId)
                .query((rs, n) -> orderRow(rs)).list();
        return assemble(rows);
    }

    private List<OrderForReturn> assemble(List<OrderRow> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Long> ids = orders.stream().map(OrderRow::id).toList();
        Map<Long, List<ReturnLine>> lines = new HashMap<>();
        jdbc.sql("SELECT id, order_id, product_id, product_name, quantity, unit_price FROM order_items "
                        + "WHERE order_id IN (:ids) ORDER BY id").param("ids", ids)
                .query((rs, n) -> {
                    lines.computeIfAbsent(rs.getLong("order_id"), key -> new ArrayList<>()).add(new ReturnLine(
                            rs.getLong("id"), rs.getLong("product_id"), rs.getString("product_name"),
                            rs.getInt("quantity"), rs.getObject("unit_price", BigDecimal.class)));
                    return 0;
                }).list();
        Map<Long, LocalDateTime> deliveredAt = new HashMap<>();
        jdbc.sql("SELECT order_id, MIN(created_at) AS at FROM order_status_history WHERE to_status = 'DELIVERED' "
                        + "AND order_id IN (:ids) GROUP BY order_id").param("ids", ids)
                .query((rs, n) -> deliveredAt.put(rs.getLong("order_id"), rs.getObject("at", LocalDateTime.class)))
                .list();
        // La fecha real del proveedor, cuando existe, prevalece sobre la de registro.
        jdbc.sql("SELECT order_id, MIN(occurred_at) AS at FROM shipment_tracking_events WHERE event_type = 'DELIVERED' "
                        + "AND outcome = 'APPLIED' AND order_id IN (:ids) GROUP BY order_id").param("ids", ids)
                .query((rs, n) -> deliveredAt.put(rs.getLong("order_id"), rs.getObject("at", LocalDateTime.class)))
                .list();
        return orders.stream().map(order -> new OrderForReturn(order.id(), order.accountId(), order.storeId(),
                order.delivered(), order.delivered() ? deliveredAt.get(order.id()) : null,
                lines.getOrDefault(order.id(), List.of()), order.pickup())).toList();
    }
}
