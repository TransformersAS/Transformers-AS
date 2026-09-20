package com.transformersas.marketplace.orders.application.dto;

/** Pedido Listo para despacho y resultado (posiblemente fallido) de la solicitud de envío. */
public record ReadyForDispatchResult(OrderStatusResult order, ShipmentOutcome shipment) {
}
