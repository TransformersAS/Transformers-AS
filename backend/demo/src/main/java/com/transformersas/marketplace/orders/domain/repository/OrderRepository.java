/** Puertos de persistencia requeridos por pedidos. */
package com.transformersas.marketplace.orders.domain.repository;

import com.transformersas.marketplace.orders.domain.model.Order;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Order save(Order order);

    List<Order> findByAccountId(Long accountId);

    Optional<Order> findByIdAndAccountId(Long id, Long accountId);
}