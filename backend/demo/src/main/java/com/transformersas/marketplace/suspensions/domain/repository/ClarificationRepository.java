package com.transformersas.marketplace.suspensions.domain.repository;

import com.transformersas.marketplace.suspensions.domain.model.ClarificationRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ClarificationRepository {

    /**
     * Guarda una solicitud abierta y devuelve su id. Si la tienda ya tiene otra abierta lanza BusinessException
     * CONFLICT CLARIFICATION_ALREADY_OPEN.
     */
    Long insert(ClarificationRequest request);

    Optional<ClarificationRequest> findById(Long id);

    Optional<ClarificationRequest> findOpenByStore(Long storeId);

    /** Solicitudes de la tienda, de la más reciente a la más antigua. */
    List<ClarificationRequest> findByStore(Long storeId);

    /** Guarda la respuesta si sigue abierta. Devuelve false si ya no lo estaba. */
    boolean answer(Long id, String response, Long respondedBy, LocalDateTime respondedAt);

    /** Marca como vencida una abierta. Devuelve false si ya no estaba abierta. */
    boolean expire(Long id);
}
