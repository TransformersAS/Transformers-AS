package com.transformersas.marketplace.orders.domain.repository;

import com.transformersas.marketplace.orders.domain.model.OrderCancellation;

import java.util.Optional;

public interface OrderCancellationRepository {

    /** Registra la cancelación. Lanza BusinessException 409 si el pedido ya tenía una (UNIQUE por pedido). */
    void insert(OrderCancellation cancellation);

    Optional<OrderCancellation> findByOrderId(Long orderId);
}
