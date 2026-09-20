/** Adaptadores JPA de los puertos de pedidos. */
package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.List;
import java.util.Optional;

public interface SpringDataOrderRepository
        extends JpaRepository<OrderEntity, Long> {

    @EntityGraph(attributePaths = "items")
    List<OrderEntity> findByAccountIdOrderByCreatedAtDescIdDesc(Long accountId);

    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findByIdAndAccountId(Long id, Long accountId);

    boolean existsByTransactionId(
            String transactionId
    );
}