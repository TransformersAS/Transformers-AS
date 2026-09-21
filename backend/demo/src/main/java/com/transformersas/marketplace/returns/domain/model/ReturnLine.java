package com.transformersas.marketplace.returns.domain.model;

import java.math.BigDecimal;

/** La línea de pedido que se devuelve, copiada al solicitar. Siempre se devuelve la cantidad completa. */
public record ReturnLine(Long orderItemId, Long productId, String productName, int quantity, BigDecimal unitPrice) {

    public ReturnLine {
        if (quantity < 1) {
            throw new IllegalArgumentException("La cantidad de una línea es al menos 1");
        }
    }

    /** Lo que se reembolsa por la línea: precio unitario por cantidad, sin envío (D4). */
    public BigDecimal refundAmount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
