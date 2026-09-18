/** Puertos de persistencia requeridos por pedidos. */
package com.transformersas.marketplace.orders.domain.repository;

import com.transformersas.marketplace.orders.domain.model.Order;

public interface OrderRepository {

    Order save(Order order);
}