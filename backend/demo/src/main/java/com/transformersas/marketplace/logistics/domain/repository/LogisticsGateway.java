package com.transformersas.marketplace.logistics.domain.repository;

import com.transformersas.marketplace.logistics.domain.model.ShipmentReceipt;
import com.transformersas.marketplace.logistics.domain.model.ShipmentRequest;

/**
 * Puerto hacia el servicio logístico externo. Hoy solo crea envíos; CU-24 (seguimiento/webhook) y CU-25 (retornos)
 * añadirán operaciones nuevas sin modificar esta.
 */
public interface LogisticsGateway {

    /**
     * Crea el envío. Idempotente: la misma idempotencyKey devuelve el mismo envío.
     *
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException rechazo definitivo
     * @throws com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException fallo temporal
     */
    ShipmentReceipt createShipment(ShipmentRequest request, String idempotencyKey);
}
