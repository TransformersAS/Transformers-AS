package com.transformersas.marketplace.suspensions.domain.model;

/**
 * Operaciones que la tienda ya tenía en curso y debe seguir atendiendo aunque se suspenda (postcondición 4, A8):
 * pedidos sin terminar, reclamaciones sin resolver y devoluciones sin cerrar.
 */
public record PendingOperations(long ordersInProgress, long openClaims, long openReturns) {
}
