/** Puertos de persistencia requeridos por pedidos. */
package com.transformersas.marketplace.orders.domain.repository;

import com.transformersas.marketplace.orders.domain.model.Order;
import com.transformersas.marketplace.orders.domain.model.OrderPaymentStatus;
import com.transformersas.marketplace.orders.domain.model.OrderSearchCriteria;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.domain.model.OrderSummary;
import com.transformersas.marketplace.orders.domain.model.PageResult;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    /** Inserta un pedido nuevo. No sirve para actualizar: los cambios usan los compare-and-set de abajo. */
    Order save(Order order);

    Optional<Order> findById(Long id);

    /** Solo devuelve el pedido si pertenece a la tienda (otra tienda equivale a inexistente). */
    Optional<Order> findByIdAndStoreId(Long id, Long storeId);

    /** Página de pedidos de una tienda, del más reciente al más antiguo (created_at desc, id desc). */
    PageResult<OrderSummary> search(OrderSearchCriteria criteria);

    /**
     * Compare-and-set atómico del estado: UPDATE condicionado por el estado esperado.
     * Devuelve false si otra petición ya cambió el pedido (el llamador responde 409).
     */
    boolean transitionStatus(Long orderId, OrderStatus from, OrderStatus to);

    /** Compare-and-set atómico del estado de pago. */
    boolean transitionPaymentStatus(Long orderId, OrderPaymentStatus from, OrderPaymentStatus to);

    /** Pedidos del comprador, del más reciente al más antiguo. */
    List<Order> findByAccountId(Long accountId);

    Optional<Order> findByIdAndAccountId(Long id, Long accountId);

    /** Compare-and-set del comprador: solo pasa de CONFIRMED a CANCELLATION_REQUESTED si el pedido es suyo. */
    boolean requestCancellation(Long id, Long accountId);

    boolean existsByIdAndAccountId(Long id, Long accountId);
}
