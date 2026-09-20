package com.transformersas.marketplace.orders.domain.model;

/**
 * Regla de consistencia de inventario (RF-113, A3): el producto debe existir y su stock no puede ser negativo.
 * No se compara contra la cantidad del pedido porque el stock ya se descontó al confirmar el pago.
 */
public final class InventoryConsistency {

    private InventoryConsistency() {
    }

    /** currentStock es null cuando el producto ya no existe. */
    public static boolean isConsistent(Integer currentStock) {
        return currentStock != null && currentStock >= 0;
    }
}
