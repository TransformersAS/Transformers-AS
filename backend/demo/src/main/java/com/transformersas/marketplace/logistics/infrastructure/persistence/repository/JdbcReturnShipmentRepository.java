package com.transformersas.marketplace.logistics.infrastructure.persistence.repository;

import com.transformersas.marketplace.logistics.domain.model.ReturnEventType;
import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.ReturnStatus;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingEvent;
import com.transformersas.marketplace.logistics.domain.model.TrackingEvidence;
import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.logistics.domain.model.TrackingSource;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;
import com.transformersas.marketplace.logistics.domain.repository.ReturnShipmentRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador JDBC del seguimiento de devoluciones (CU-25). Las transiciones son compare-and-set sobre el estado y la
 * idempotencia de cada actualización la garantiza la UNIQUE (devolución, id de evento) de la BD.
 */
@Repository
public class JdbcReturnShipmentRepository implements ReturnShipmentRepository {

    private static final String SHIPMENT_COLUMNS = """
            id, return_id, buyer_account_id, store_id, provider_return_id, tracking_code, status, failed_pickups,
            pickup_stopped, picked_up_at, delivered_at, created_at, updated_at""";

    private static final String EVENT_COLUMNS = """
            id, return_shipment_id, provider_event_id, event_type, occurred_at, received_at, source, outcome,
            description, location, evidence_type, evidence_reference, correlation_id""";

    private final JdbcClient jdbc;

    public JdbcReturnShipmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(Long id) {
        jdbc.sql("SELECT id FROM return_shipments WHERE id = ? FOR UPDATE").param(id).query().singleRow();
    }

    @Override
    public Insertion insertIfAbsent(ReturnShipment shipment) {
        try {
            jdbc.sql("""
                            INSERT INTO return_shipments (return_id, buyer_account_id, store_id, provider_return_id,
                                tracking_code, status, failed_pickups, pickup_stopped, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, 0, FALSE, ?, ?)""")
                    .params(shipment.returnId(), shipment.buyerAccountId(), shipment.storeId(),
                            shipment.providerReturnId(), shipment.trackingCode(), shipment.status().name(),
                            shipment.createdAt(), shipment.updatedAt())
                    .update();
            return new Insertion(findByReturnId(shipment.returnId()).orElseThrow(), true);
        } catch (DuplicateKeyException duplicate) {
            // Carrera o reintento de CU-19: la devolución ya tenía seguimiento; se devuelve el existente.
            return new Insertion(findByReturnId(shipment.returnId()).orElseThrow(() -> duplicate), false);
        }
    }

    @Override
    public Optional<ReturnShipment> findById(Long id) {
        return findShipment("id = ?", id);
    }

    @Override
    public Optional<ReturnShipment> findByReturnId(Long returnId) {
        return findShipment("return_id = ?", returnId);
    }

    @Override
    public Optional<ReturnShipment> findByProviderReturnId(String providerReturnId) {
        return findShipment("provider_return_id = ?", providerReturnId);
    }

    private Optional<ReturnShipment> findShipment(String condition, Object value) {
        return jdbc.sql("SELECT " + SHIPMENT_COLUMNS + " FROM return_shipments WHERE " + condition)
                .param(value)
                .query(JdbcReturnShipmentRepository::shipment)
                .optional();
    }

    @Override
    public boolean applyChange(Long id, ReturnStatus from, Change change) {
        int updated = jdbc.sql("""
                        UPDATE return_shipments
                        SET status = ?, failed_pickups = ?, pickup_stopped = ?,
                            picked_up_at = COALESCE(picked_up_at, ?), delivered_at = COALESCE(delivered_at, ?),
                            updated_at = ?
                        WHERE id = ? AND status = ?""")
                .params(change.to().name(), change.failedPickups(), change.pickupStopped(), change.pickedUpAt(),
                        change.deliveredAt(), LocalDateTime.now(), id, from.name())
                .update();
        return updated == 1;
    }

    @Override
    public EventInsertion insertEventIfAbsent(ReturnTrackingEvent event) {
        try {
            jdbc.sql("""
                            INSERT INTO return_tracking_events (return_shipment_id, provider_event_id, event_type,
                                occurred_at, received_at, source, outcome, description, location, evidence_type,
                                evidence_reference, correlation_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(event.returnShipmentId(), event.providerEventId(), event.type().name(),
                            event.occurredAt(), event.receivedAt(), event.source().name(), event.outcome().name(),
                            event.description(), event.location(),
                            event.evidence() == null ? null : event.evidence().type(),
                            event.evidence() == null ? null : event.evidence().reference(), event.correlationId())
                    .update();
            return new EventInsertion(findEvent(event.returnShipmentId(), event.providerEventId()).orElseThrow(), true);
        } catch (DuplicateKeyException duplicate) {
            return new EventInsertion(findEvent(event.returnShipmentId(), event.providerEventId())
                    .orElseThrow(() -> duplicate), false);
        }
    }

    private Optional<ReturnTrackingEvent> findEvent(Long returnShipmentId, String providerEventId) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS
                        + " FROM return_tracking_events WHERE return_shipment_id = ? AND provider_event_id = ?")
                .params(returnShipmentId, providerEventId)
                .query(JdbcReturnShipmentRepository::event)
                .optional();
    }

    @Override
    public void updateEventOutcome(Long eventId, TrackingOutcome outcome) {
        jdbc.sql("UPDATE return_tracking_events SET outcome = ? WHERE id = ?")
                .params(outcome.name(), eventId).update();
    }

    @Override
    public List<ReturnTrackingEvent> findEvents(Long returnShipmentId) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS
                        + " FROM return_tracking_events WHERE return_shipment_id = ? ORDER BY occurred_at, id")
                .param(returnShipmentId)
                .query(JdbcReturnShipmentRepository::event)
                .list();
    }

    @Override
    public List<ReturnShipment> findDueForPolling(LocalDateTime olderThan, int limit) {
        return jdbc.sql("SELECT " + SHIPMENT_COLUMNS + """
                         FROM return_shipments
                        WHERE tracking_active = TRUE AND (last_polled_at IS NULL OR last_polled_at < ?)
                        ORDER BY last_polled_at IS NOT NULL, last_polled_at, id
                        LIMIT ?""")
                .params(olderThan, limit)
                .query(JdbcReturnShipmentRepository::shipment)
                .list();
    }

    @Override
    public Optional<TrackingState> findState(Long id) {
        return jdbc.sql("SELECT id, tracking_active, last_polled_at, poll_failures FROM return_shipments WHERE id = ?")
                .param(id)
                .query((rs, row) -> new TrackingState(rs.getLong("id"), rs.getBoolean("tracking_active"),
                        rs.getObject("last_polled_at", LocalDateTime.class), rs.getInt("poll_failures")))
                .optional();
    }

    @Override
    public void markPolled(Long id, boolean succeeded, LocalDateTime at) {
        jdbc.sql("""
                        UPDATE return_shipments
                        SET last_polled_at = ?, poll_failures = CASE WHEN ? THEN 0 ELSE poll_failures + 1 END
                        WHERE id = ?""")
                .params(at, succeeded, id).update();
    }

    @Override
    public void closeTracking(Long id) {
        jdbc.sql("UPDATE return_shipments SET tracking_active = FALSE WHERE id = ?").param(id).update();
    }

    private static ReturnShipment shipment(ResultSet rs, int row) throws SQLException {
        return new ReturnShipment(rs.getLong("id"), rs.getLong("return_id"), rs.getLong("buyer_account_id"),
                rs.getLong("store_id"), rs.getString("provider_return_id"), rs.getString("tracking_code"),
                ReturnStatus.valueOf(rs.getString("status")), rs.getInt("failed_pickups"),
                rs.getBoolean("pickup_stopped"), rs.getObject("picked_up_at", LocalDateTime.class),
                rs.getObject("delivered_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private static ReturnTrackingEvent event(ResultSet rs, int row) throws SQLException {
        String evidenceType = rs.getString("evidence_type");
        String evidenceReference = rs.getString("evidence_reference");
        boolean hasEvidence = evidenceType != null || evidenceReference != null;
        return new ReturnTrackingEvent(rs.getLong("id"), rs.getLong("return_shipment_id"),
                rs.getString("provider_event_id"), ReturnEventType.valueOf(rs.getString("event_type")),
                rs.getObject("occurred_at", LocalDateTime.class), rs.getObject("received_at", LocalDateTime.class),
                TrackingSource.valueOf(rs.getString("source")), TrackingOutcome.valueOf(rs.getString("outcome")),
                rs.getString("description"), rs.getString("location"),
                hasEvidence ? new TrackingEvidence(evidenceType, evidenceReference) : null,
                rs.getString("correlation_id"));
    }
}
