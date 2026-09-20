package com.transformersas.marketplace.logistics.domain.repository;

import com.transformersas.marketplace.logistics.domain.model.ReturnShipment;
import com.transformersas.marketplace.logistics.domain.model.ReturnStatus;
import com.transformersas.marketplace.logistics.domain.model.ReturnTrackingEvent;
import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Seguimiento logístico de devoluciones y su línea de tiempo (CU-25). */
public interface ReturnShipmentRepository {

    /** Bloquea la fila del seguimiento hasta el fin de la transacción: serializa las actualizaciones concurrentes. */
    void lock(Long id);

    /** Inserta el seguimiento o, si la devolución ya lo tenía (UNIQUE return_id), devuelve el existente. */
    Insertion insertIfAbsent(ReturnShipment shipment);

    Optional<ReturnShipment> findById(Long id);

    Optional<ReturnShipment> findByReturnId(Long returnId);

    Optional<ReturnShipment> findByProviderReturnId(String providerReturnId);

    /**
     * Compare-and-set: UPDATE condicionado por el estado esperado. Devuelve false si otra petición ya cambió el
     * seguimiento (el llamador reintenta). pickedUpAt/deliveredAt nulos conservan el valor guardado.
     */
    boolean applyChange(Long id, ReturnStatus from, Change change);

    EventInsertion insertEventIfAbsent(ReturnTrackingEvent event);

    void updateEventOutcome(Long eventId, TrackingOutcome outcome);

    /** Actualizaciones de la devolución en orden cronológico (occurred_at, id). */
    List<ReturnTrackingEvent> findEvents(Long returnShipmentId);

    /** Devoluciones activas nunca consultadas o consultadas antes de olderThan, las más atrasadas primero. */
    List<ReturnShipment> findDueForPolling(LocalDateTime olderThan, int limit);

    /** Control de consulta: si sigue activa, cuándo se consultó por última vez y cuántas consultas seguidas fallaron. */
    Optional<TrackingState> findState(Long id);

    void markPolled(Long id, boolean succeeded, LocalDateTime at);

    void closeTracking(Long id);

    record Change(ReturnStatus to, int failedPickups, boolean pickupStopped, LocalDateTime pickedUpAt,
                  LocalDateTime deliveredAt) {
    }

    record Insertion(ReturnShipment shipment, boolean created) {
    }

    record EventInsertion(ReturnTrackingEvent event, boolean created) {
    }
}
