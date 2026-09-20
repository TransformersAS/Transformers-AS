package com.transformersas.marketplace.orders.domain.repository;

import com.transformersas.marketplace.orders.domain.model.OrderIssue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderIssueRepository {

    /**
     * Inserta la novedad. Para INVENTORY_INCONSISTENCY existe a lo sumo una ABIERTA por pedido: si ya la había,
     * devuelve la existente (created = false). Los demás tipos siempre se insertan.
     */
    Registration register(OrderIssue issue);

    Optional<OrderIssue> findByIdAndOrderId(Long issueId, Long orderId);

    List<OrderIssue> findByOrderId(Long orderId);

    List<OrderIssue> findOpenByOrderId(Long orderId);

    /** Compare-and-set OPEN -> RESOLVED. Devuelve false si ya estaba resuelta. */
    boolean resolve(Long issueId, Long resolvedById, LocalDateTime resolvedAt);

    record Registration(OrderIssue issue, boolean created) {
    }
}
