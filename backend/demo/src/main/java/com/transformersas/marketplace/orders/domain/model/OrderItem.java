package com.transformersas.marketplace.orders.domain.model;

import java.math.BigDecimal;

/**
 * Línea de un pedido con los datos del producto copiados al comprar. unitCost es el costo unitario interno vigente en
 * ese momento (CU-26): nulo si el vendedor no lo había registrado o el pedido es anterior a CU-26. Es información del
 * vendedor; las respuestas al comprador no lo incluyen.
 */
public record OrderItem(
        Long productId,
        String productName,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal,
        BigDecimal unitCost
) {

    /** Línea sin costo conocido. */
    public OrderItem(Long productId, String productName, Integer quantity, BigDecimal unitPrice, BigDecimal subtotal) {
        this(productId, productName, quantity, unitPrice, subtotal, null);
    }
}
