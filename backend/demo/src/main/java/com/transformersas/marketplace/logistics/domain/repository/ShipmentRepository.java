package com.transformersas.marketplace.logistics.domain.repository;

import com.transformersas.marketplace.logistics.domain.model.Shipment;

import java.util.Optional;

public interface ShipmentRepository {

    Optional<Shipment> findByOrderId(Long orderId);

    /**
     * Inserta el envío o, si el pedido ya tenía uno (carrera), devuelve el existente sin duplicar.
     * created indica si esta llamada fue la que lo insertó.
     */
    Insertion insertIfAbsent(Shipment shipment);

    record Insertion(Shipment shipment, boolean created) {
    }
}
