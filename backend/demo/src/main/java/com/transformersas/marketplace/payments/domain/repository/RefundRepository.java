package com.transformersas.marketplace.payments.domain.repository;

import com.transformersas.marketplace.payments.domain.model.Refund;

import java.util.Optional;

public interface RefundRepository {

    /**
     * Inserta el reembolso o, si ya existía uno con la misma idempotencyKey, devuelve el existente sin duplicar.
     * created indica si esta llamada fue la que lo insertó.
     */
    Insertion insertIfAbsent(Refund refund);

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    Optional<Refund> findById(Long id);

    /** Compare-and-set hacia COMPLETED. Devuelve false si ya estaba completado (solo el primero audita). Cuenta el intento. */
    boolean markCompleted(Long id, String providerReference);

    /** El proveedor aceptó sin confirmar. No cambia un reembolso ya completado. Cuenta el intento. */
    void markPending(Long id);

    /** El intento falló. No cambia un reembolso ya completado. Cuenta el intento. */
    void markFailed(Long id, String error);

    record Insertion(Refund refund, boolean created) {
    }
}
