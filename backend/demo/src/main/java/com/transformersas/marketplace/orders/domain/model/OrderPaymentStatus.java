package com.transformersas.marketplace.orders.domain.model;

/** Estado financiero del pedido. El checkout solo crea pedidos con pago aprobado (D4). */
public enum OrderPaymentStatus { APPROVED, REFUND_PENDING, REFUNDED }
