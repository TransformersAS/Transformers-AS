package com.transformersas.marketplace.orders.application.dto;

/** Acción de un vendedor sobre un pedido de su tienda: la tienda y el actor salen de la identidad, nunca del request. */
public record OrderActionCommand(Long orderId, Long storeId, Long actorId) {
}
