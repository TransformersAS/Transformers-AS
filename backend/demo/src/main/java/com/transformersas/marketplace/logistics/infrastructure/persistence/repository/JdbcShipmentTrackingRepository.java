package com.transformersas.marketplace.logistics.infrastructure.persistence.repository;

import com.transformersas.marketplace.logistics.domain.model.ShipmentEventType;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvent;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvidence;
import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.logistics.domain.model.TrackingSource;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;
import com.transformersas.marketplace.logistics.domain.repository.ShipmentTrackingRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador JDBC del seguimiento de pedidos: línea de tiempo append-only sin entidad JPA. La idempotencia la
 * garantiza la UNIQUE (envío, id de evento) de la BD, no una lectura previa.
 */
@Repository
public class JdbcShipmentTrackingRepository implements ShipmentTrackingRepository {

    private static final String EVENT_COLUMNS = """
            id, shipment_id, order_id, provider_event_id, event_type, occurred_at, received_at, source, outcome,
            description, location, evidence_type, evidence_reference, correlation_id""";

    private final JdbcClient jdbc;

    public JdbcShipmentTrackingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(Long shipmentId) {
        jdbc.sql("SELECT id FROM shipments WHERE id = ? FOR UPDATE").param(shipmentId).query().singleRow();
    }

    @Override
    public Insertion insertEventIfAbsent(TrackingEvent event) {
        try {
            jdbc.sql("""
                            INSERT INTO shipment_tracking_events (shipment_id, order_id, provider_event_id, event_type,
                                occurred_at, received_at, source, outcome, description, location, evidence_type,
                                evidence_reference, correlation_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(event.shipmentId(), event.orderId(), event.providerEventId(), event.type().name(),
                            event.occurredAt(), event.receivedAt(), event.source().name(), event.outcome().name(),
                            event.description(), event.location(),
                            event.evidence() == null ? null : event.evidence().type(),
                            event.evidence() == null ? null : event.evidence().reference(), event.correlationId())
                    .update();
            return new Insertion(findEvent(event.shipmentId(), event.providerEventId()).orElseThrow(), true);
        } catch (DuplicateKeyException duplicate) {
            // El proveedor repitió la actualización (webhook + consulta, o reintento): se devuelve la existente.
            return new Insertion(findEvent(event.shipmentId(), event.providerEventId())
                    .orElseThrow(() -> duplicate), false);
        }
    }

    private Optional<TrackingEvent> findEvent(Long shipmentId, String providerEventId) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS
                        + " FROM shipment_tracking_events WHERE shipment_id = ? AND provider_event_id = ?")
                .params(shipmentId, providerEventId)
                .query(JdbcShipmentTrackingRepository::event)
                .optional();
    }

    @Override
    public void updateEventOutcome(Long eventId, TrackingOutcome outcome) {
        jdbc.sql("UPDATE shipment_tracking_events SET outcome = ? WHERE id = ?")
                .params(outcome.name(), eventId).update();
    }

    @Override
    public List<TrackingEvent> findEvents(Long shipmentId) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS
                        + " FROM shipment_tracking_events WHERE shipment_id = ? ORDER BY occurred_at, id")
                .param(shipmentId)
                .query(JdbcShipmentTrackingRepository::event)
                .list();
    }

    @Override
    public Optional<TrackingState> findState(Long shipmentId) {
        return jdbc.sql("""
                        SELECT id, tracking_active, last_polled_at, poll_failures FROM shipments WHERE id = ?""")
                .param(shipmentId)
                .query(JdbcShipmentTrackingRepository::state)
                .optional();
    }

    @Override
    public List<TrackingState> findDueForPolling(LocalDateTime olderThan, int limit) {
        return jdbc.sql("""
                        SELECT id, tracking_active, last_polled_at, poll_failures FROM shipments
                        WHERE tracking_active = TRUE AND (last_polled_at IS NULL OR last_polled_at < ?)
                        ORDER BY last_polled_at IS NOT NULL, last_polled_at, id
                        LIMIT ?""")
                .params(olderThan, limit)
                .query(JdbcShipmentTrackingRepository::state)
                .list();
    }

    @Override
    public void markPolled(Long shipmentId, boolean succeeded, LocalDateTime at) {
        jdbc.sql("""
                        UPDATE shipments
                        SET last_polled_at = ?, poll_failures = CASE WHEN ? THEN 0 ELSE poll_failures + 1 END
                        WHERE id = ?""")
                .params(at, succeeded, shipmentId).update();
    }

    @Override
    public void closeTracking(Long shipmentId) {
        jdbc.sql("UPDATE shipments SET tracking_active = FALSE WHERE id = ?").param(shipmentId).update();
    }

    private static TrackingState state(ResultSet rs, int row) throws SQLException {
        return new TrackingState(rs.getLong("id"), rs.getBoolean("tracking_active"),
                rs.getObject("last_polled_at", LocalDateTime.class), rs.getInt("poll_failures"));
    }

    private static TrackingEvent event(ResultSet rs, int row) throws SQLException {
        String evidenceType = rs.getString("evidence_type");
        String evidenceReference = rs.getString("evidence_reference");
        boolean hasEvidence = evidenceType != null || evidenceReference != null;
        return new TrackingEvent(rs.getLong("id"), rs.getLong("shipment_id"), rs.getLong("order_id"),
                rs.getString("provider_event_id"), ShipmentEventType.valueOf(rs.getString("event_type")),
                rs.getObject("occurred_at", LocalDateTime.class), rs.getObject("received_at", LocalDateTime.class),
                TrackingSource.valueOf(rs.getString("source")), TrackingOutcome.valueOf(rs.getString("outcome")),
                rs.getString("description"), rs.getString("location"),
                hasEvidence ? new TrackingEvidence(evidenceType, evidenceReference) : null,
                rs.getString("correlation_id"));
    }
}
