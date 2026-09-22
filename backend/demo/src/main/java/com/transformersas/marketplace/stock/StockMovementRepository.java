package com.transformersas.marketplace.stock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** Los 50 movimientos más recientes de un producto, del más nuevo al más antiguo. */
    List<StockMovement> findTop50ByProductIdOrderByIdDesc(Long productId);
}
