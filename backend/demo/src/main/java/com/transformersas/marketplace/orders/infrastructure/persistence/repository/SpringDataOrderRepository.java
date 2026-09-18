/** Adaptadores JPA de los puertos de pedidos. */
package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataOrderRepository
        extends JpaRepository<OrderEntity, Long> {

    boolean existsByTransactionId(
            String transactionId
    );
}