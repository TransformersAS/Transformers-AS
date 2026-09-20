/** Adaptadores JPA de los puertos de pedidos. */
package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface SpringDataOrderRepository
        extends JpaRepository<OrderEntity, Long> {

    @EntityGraph(attributePaths = "items")
    List<OrderEntity> findByAccountIdOrderByCreatedAtDescIdDesc(Long accountId);

    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findByIdAndAccountId(Long id, Long accountId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE orders SET status = 'CANCELLATION_REQUESTED'
            WHERE id = :id AND account_id = :accountId AND status = 'CONFIRMED'
            """, nativeQuery = true)
    int requestCancellation(@Param("id") Long id, @Param("accountId") Long accountId);

    boolean existsByIdAndAccountId(Long id, Long accountId);

    boolean existsByTransactionId(
            String transactionId
    );
}