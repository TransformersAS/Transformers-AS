package com.transformersas.marketplace.orders.domain.model;

/**
 * Datos de entrega copiados al crear el pedido (D3). Inmutables: no existe operación que los modifique.
 * toString no expone datos personales para que no lleguen a los logs.
 */
public record DeliverySnapshot(
        String recipientName,
        String street,
        String city,
        String department,
        String postalCode,
        String phone
) {
    @Override
    public String toString() {
        return "DeliverySnapshot[redacted]";
    }
}
