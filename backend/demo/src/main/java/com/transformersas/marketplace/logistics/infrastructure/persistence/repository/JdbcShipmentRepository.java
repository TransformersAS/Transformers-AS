package com.transformersas.marketplace.logistics.infrastructure.persistence.repository;

import com.transformersas.marketplace.logistics.domain.model.Shipment;
import com.transformersas.marketplace.logistics.domain.model.ShipmentStatus;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/** Adaptador JDBC de envíos. La unicidad por pedido la garantiza la BD (UNIQUE), no una lectura previa. */
@Repository
public class JdbcShipmentRepository implements ShipmentRepository {

    private final JdbcClient jdbc;

    public JdbcShipmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Shipment> findByOrderId(Long orderId) {
        return jdbc.sql("""
                        SELECT id, order_id, provider_shipment_id, tracking_code, idempotency_key, status, created_at
                        FROM shipments WHERE order_id = ?""")
                .param(orderId)
                .query((rs, row) -> new Shipment(rs.getLong("id"), rs.getLong("order_id"),
                        rs.getString("provider_shipment_id"), rs.getString("tracking_code"),
                        rs.getString("idempotency_key"), ShipmentStatus.valueOf(rs.getString("status")),
                        rs.getObject("created_at", LocalDateTime.class)))
                .optional();
    }

    @Override
    public Insertion insertIfAbsent(Shipment shipment) {
        try {
            jdbc.sql("""
                            INSERT INTO shipments (order_id, provider_shipment_id, tracking_code, idempotency_key,
                                                   status, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)""")
                    .params(shipment.orderId(), shipment.providerShipmentId(), shipment.trackingCode(),
                            shipment.idempotencyKey(), shipment.status().name(), shipment.createdAt())
                    .update();
            return new Insertion(findByOrderId(shipment.orderId()).orElseThrow(), true);
        } catch (DuplicateKeyException duplicate) {
            // Carrera: otra petición registró el envío del mismo pedido. Se devuelve el existente.
            return new Insertion(findByOrderId(shipment.orderId()).orElseThrow(() -> duplicate), false);
        }
    }
}
