package com.transformersas.marketplace.orders.application.dto;

/**
 * Resultado de solicitar el envío. FAILED no es un error HTTP en listo-para-despacho (A5): el pedido sigue
 * Listo para despacho y el vendedor puede reintentar. created es false cuando se reutilizó un envío existente (A6).
 */
public record ShipmentOutcome(Status status, String shipmentId, String trackingCode, String message, boolean created) {

    public enum Status { CREATED, FAILED }

    public static ShipmentOutcome created(String shipmentId, String trackingCode, boolean created) {
        return new ShipmentOutcome(Status.CREATED, shipmentId, trackingCode, null, created);
    }

    public static ShipmentOutcome failed(String message) {
        return new ShipmentOutcome(Status.FAILED, null, null, message, false);
    }
}
