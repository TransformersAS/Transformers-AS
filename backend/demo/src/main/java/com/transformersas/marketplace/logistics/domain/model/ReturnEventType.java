package com.transformersas.marketplace.logistics.domain.model;

import java.util.Locale;
import java.util.Optional;

/** Tipos de actualización que informa el servicio logístico sobre el retorno de una devolución (RF-110). */
public enum ReturnEventType {
    PICKUP_SCHEDULED,
    PICKED_UP,
    IN_TRANSIT,
    INCIDENT,
    PICKUP_FAILED,
    DELIVERED_TO_SELLER;

    /** Tipo desconocido = vacío: el proveedor puede añadir tipos nuevos sin romper el marketplace. */
    public static Optional<ReturnEventType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
