package com.transformersas.marketplace.claims;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findByBuyerAccountIdOrderByIdDesc(Long buyerAccountId);

    List<Claim> findByStoreIdOrderByIdDesc(Long storeId);

    /** Cola de soporte: las más antiguas primero. */
    List<Claim> findByStatusOrderByIdAsc(ClaimStatus status);

    /** Solo si la reclamación es del comprador; la de otro equivale a inexistente. */
    Optional<Claim> findByIdAndBuyerAccountId(Long id, Long buyerAccountId);

    /** Solo si la reclamación es de la tienda; la de otra equivale a inexistente. */
    Optional<Claim> findByIdAndStoreId(Long id, Long storeId);

    /** ¿Ya hay una reclamación sin terminar (estado distinto del indicado) para ese producto de esa compra? */
    boolean existsByOrderIdAndProductIdAndStatusNot(Long orderId, Long productId, ClaimStatus status);
}
