package com.transformersas.marketplace.suspensions.domain.model;

import java.time.LocalDateTime;

/**
 * Cierre de una evaluación: sin medida (A1), con la tienda operando igual, o con la suspensión que se aplicó. Queda
 * registrada aunque no cambie nada (postcondición 1).
 */
public record StoreReview(Long id, Long storeId, Long reviewedBy, Decision decision, String note, Long suspensionId,
                          LocalDateTime createdAt) {

    public enum Decision { NO_ACTION, SUSPENDED }
}
