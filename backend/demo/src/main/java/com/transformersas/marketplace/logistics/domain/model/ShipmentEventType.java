package com.transformersas.marketplace.logistics.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Tipos de actualización que informa el servicio logístico sobre un envío (RF-116, RF-118). Salvo
 * NEXT_ATTEMPT_SCHEDULED (A3, solo informativo), cada tipo corresponde al estado de pedido del mismo nombre.
 */
public enum ShipmentEventType {
    PICKED_UP,
    IN_TRANSIT,
    DELIVERY_EXCEPTION,
    DELIVERY_ATTEMPT_FAILED,
    NEXT_ATTEMPT_SCHEDULED,
    DELIVERED,
    RETURNED_TO_SELLER;

    /** Tipo desconocido = vacío: el proveedor puede añadir tipos nuevos sin romper el marketplace. */
    public static Optional<ShipmentEventType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** Estados finales: después de ellos ya no hay nada que consultar. */
    public boolean closesTracking() {
        return this == DELIVERED || this == RETURNED_TO_SELLER;
    }
}
