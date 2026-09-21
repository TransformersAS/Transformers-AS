package com.transformersas.marketplace.orders.application.dto;

import com.transformersas.marketplace.logistics.application.dto.RefreshOutcome;
import com.transformersas.marketplace.logistics.application.dto.ShipmentTracking;
import com.transformersas.marketplace.orders.domain.model.Order;

/**
 * Seguimiento de un pedido para su comprador o su tienda (RF-117): estado vigente más el detalle logístico.
 * shipment es null si el pedido todavía no tiene envío; refresh solo se informa cuando el usuario pidió actualizar.
 */
public record OrderTracking(Order order, ShipmentTracking shipment, RefreshOutcome refresh) {
}
