package com.transformersas.marketplace.returns.domain.repository;

import com.transformersas.marketplace.returns.domain.model.InformationRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnEvent;
import com.transformersas.marketplace.returns.domain.model.ReturnEventType;
import com.transformersas.marketplace.returns.domain.model.ReturnRequest;
import com.transformersas.marketplace.returns.domain.model.ReturnStatus;
import com.transformersas.marketplace.shared.audit.ActorType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de las devoluciones (CU-19). Los métodos {@code lock*} bloquean la fila hasta el fin de la transacción
 * (SELECT ... FOR UPDATE) y solo se llaman dentro de una: quien modifica una devolución la bloquea, aplica la
 * transición del agregado y la guarda, con lo que dos peticiones sobre la misma devolución se serializan.
 */
public interface ReturnRequestRepository {

    /** {@code created} es falso si la línea ya tenía una solicitud: entonces {@code request} es la existente (A3, A10). */
    record Insertion(ReturnRequest request, boolean created) {
    }

    /** Un hecho de la línea de tiempo tal como quedó guardado. */
    record TimelineEntry(Long id, ReturnEventType type, ReturnStatus from, ReturnStatus to, ActorType actorType,
                         Long actorId, String details, LocalDateTime createdAt) {
    }

    /** Una solicitud de información, abierta o respondida. {@code status}: OPEN o ANSWERED. */
    record InformationRecord(Long id, String message, LocalDateTime requestedAt, LocalDateTime dueAt, String status,
                             String responseText, LocalDateTime respondedAt) {
    }

    /** Guarda una solicitud nueva y le asigna su id; si la línea ya tenía una, devuelve esa sin cambiar nada. */
    Insertion insertIfAbsent(ReturnRequest request);

    Optional<ReturnRequest> findById(Long id);

    Optional<ReturnRequest> lockById(Long id);

    Optional<ReturnRequest> findByOrderItemId(Long orderItemId);

    Optional<ReturnRequest> lockByOrderItemId(Long orderItemId);

    List<ReturnRequest> findByOrderItemIds(Collection<Long> orderItemIds);

    /** Las devoluciones del comprador, de la más reciente a la más antigua. */
    List<ReturnRequest> findByBuyer(Long buyerAccountId);

    /** Las de una tienda, de la más antigua a la más reciente (la que lleva más tiempo esperando, primero). */
    List<ReturnRequest> findByStore(Long storeId, ReturnStatus statusOrNull);

    /** Guarda los cambios de una devolución ya existente (la fila debe estar bloqueada por quien la modifica). */
    void save(ReturnRequest request);

    Long addInformationRequest(Long returnId, InformationRequest request);

    void answerInformationRequest(Long informationId, String text, LocalDateTime at);

    List<InformationRecord> informationHistory(Long returnId);

    void appendEvent(Long returnId, ReturnEvent event, String correlationId, LocalDateTime at);

    List<TimelineEntry> timeline(Long returnId);

    /**
     * Ids de las devoluciones cuya próxima acción venció (fin de la inspección o reintento del reembolso), las más
     * antiguas primero y como máximo {@code limit}. Las bloquea con SKIP LOCKED: si otra réplica ya tiene alguna, se salta
     * en vez de esperar, así dos réplicas nunca procesan lo mismo. Dentro de una transacción.
     */
    List<Long> lockDueIds(LocalDateTime now, int limit);
}
