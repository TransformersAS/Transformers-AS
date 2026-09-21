package com.transformersas.marketplace.logistics.domain.repository;

import com.transformersas.marketplace.logistics.domain.model.TrackingEvent;
import com.transformersas.marketplace.logistics.domain.model.TrackingOutcome;
import com.transformersas.marketplace.logistics.domain.model.TrackingState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Línea de tiempo y control de consulta del seguimiento de pedidos (CU-24). */
public interface ShipmentTrackingRepository {

    /**
     * Bloquea la fila del envío hasta el fin de la transacción (SELECT ... FOR UPDATE). Serializa las actualizaciones
     * concurrentes del mismo envío antes de tocar el pedido: sin esto, dos transacciones tomarían primero un lock
     * compartido por las claves foráneas y después pedirían el exclusivo, y una moriría por interbloqueo.
     */
    void lock(Long shipmentId);

    /**
     * Inserta la actualización o, si el proveedor ya la había enviado (mismo id de evento para el envío), deja la
     * existente sin duplicar. created indica si esta llamada fue la que la insertó.
     */
    Insertion insertEventIfAbsent(TrackingEvent event);

    void updateEventOutcome(Long eventId, TrackingOutcome outcome);

    /** Actualizaciones del envío en orden cronológico (occurred_at, id). */
    List<TrackingEvent> findEvents(Long shipmentId);

    Optional<TrackingState> findState(Long shipmentId);

    /** Envíos activos nunca consultados o consultados antes de olderThan, los más atrasados primero. */
    List<TrackingState> findDueForPolling(LocalDateTime olderThan, int limit);

    /** Registra el resultado de una consulta al proveedor: el éxito reinicia los fallos, el fallo los incrementa. */
    void markPolled(Long shipmentId, boolean succeeded, LocalDateTime at);

    /** Marca el envío como finalizado: el marketplace deja de consultarlo. */
    void closeTracking(Long shipmentId);

    record Insertion(TrackingEvent event, boolean created) {
    }
}
