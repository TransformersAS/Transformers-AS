/** Adaptadores JPA de los puertos de pedidos. */
package com.transformersas.marketplace.orders.infrastructure.persistence.repository;

import com.transformersas.marketplace.orders.domain.model.OrderPaymentStatus;
import com.transformersas.marketplace.orders.domain.model.OrderStatus;
import com.transformersas.marketplace.orders.infrastructure.persistence.entity.OrderEntity;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataOrderRepository
        extends JpaRepository<OrderEntity, Long>, JpaSpecificationExecutor<OrderEntity> {

    boolean existsByTransactionId(String transactionId);

    Optional<OrderEntity> findByIdAndStoreId(Long id, Long storeId);

    /** Compare-and-set: solo cambia si el estado actual es el esperado. Devuelve las filas afectadas (0 o 1). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderEntity o set o.status = :to where o.id = :id and o.status = :from")
    int transitionStatus(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderEntity o set o.paymentStatus = :to where o.id = :id and o.paymentStatus = :from")
    int transitionPaymentStatus(@Param("id") Long id, @Param("from") OrderPaymentStatus from,
                                @Param("to") OrderPaymentStatus to);

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
}
