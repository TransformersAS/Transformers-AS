package com.transformersas.marketplace.logistics.domain.repository;

import com.transformersas.marketplace.logistics.domain.model.Shipment;

import java.util.Optional;

public interface ShipmentRepository {

    Optional<Shipment> findByOrderId(Long orderId);

    Optional<Shipment> findById(Long id);

    /** Identifica el envío por la referencia que asignó el servicio logístico (CU-24, paso 2). */
    Optional<Shipment> findByProviderShipmentId(String providerShipmentId);

    /**
     * Inserta el envío o, si el pedido ya tenía uno (carrera), devuelve el existente sin duplicar.
     * created indica si esta llamada fue la que lo insertó.
     */
    Insertion insertIfAbsent(Shipment shipment);

    record Insertion(Shipment shipment, boolean created) {
    }
}
