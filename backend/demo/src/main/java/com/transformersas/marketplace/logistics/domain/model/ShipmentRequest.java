package com.transformersas.marketplace.logistics.domain.model;

import java.util.List;

/**
 * Datos necesarios para que el servicio logístico cree un envío. Lo construye el módulo dueño del pedido a partir
 * del snapshot de entrega; logística nunca lee tablas de pedidos.
 */
public record ShipmentRequest(
        Long orderId,
        String shippingMethod,
        Recipient recipient,
        List<Item> items
) {

    /** Clave de idempotencia estable por pedido: repetir la solicitud nunca crea otro envío. */
    public String idempotencyKey() {
        return "order-" + orderId;
    }

    /** Destinatario: dato personal, por eso toString no lo expone. */
    public record Recipient(String name, String street, String city, String department, String postalCode,
                            String phone) {
        @Override
        public String toString() {
            return "Recipient[redacted]";
        }
    }

    public record Item(String name, int quantity) {
    }
}
