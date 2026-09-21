package com.transformersas.marketplace.inventory.domain.repository;

/** Puerto de movimientos de stock. Cada operación es un único UPDATE atómico, nunca leer-modificar-escribir. */
public interface StockAdjustmentRepository {

    /**
     * Suma unidades al stock con UPDATE atómico (stock = stock + n). Devuelve false si el producto ya no existe.
     */
    boolean increase(Long productId, int quantity);
}
