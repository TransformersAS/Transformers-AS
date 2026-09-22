package com.transformersas.marketplace.returns.domain.port;

import java.util.List;
import java.util.Optional;

/**
 * Lectura de pedidos para devoluciones. La implementa el módulo de pedidos: la fecha de entrega sale de
 * shipment_tracking_events (evento DELIVERED con resultado APPLIED, por occurred_at) y, si no hay, de
 * order_status_history.
 */
public interface OrderForReturnReader {

    /** El pedido si es del comprador; vacío si no existe o es de otra cuenta (no se distingue, para no revelar nada). */
    Optional<OrderForReturn> findForBuyer(Long orderId, Long buyerAccountId);

    /** Los pedidos del comprador, del más reciente al más antiguo (RF-048). */
    List<OrderForReturn> findByBuyer(Long buyerAccountId);
}
