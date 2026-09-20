package com.transformersas.marketplace.logistics.domain.model;

/**
 * El pedido o la devolución cambió de estado mientras se aplicaba la actualización (perdió el compare-and-set). La
 * transacción se revierte completa, incluido el evento, y el caso de uso la reintenta leyendo el estado nuevo.
 */
public class TrackingConflictException extends RuntimeException {

    public TrackingConflictException(String subject, Long id) {
        super(subject + " " + id + " cambió de estado mientras se aplicaba la actualización logística");
    }
}
