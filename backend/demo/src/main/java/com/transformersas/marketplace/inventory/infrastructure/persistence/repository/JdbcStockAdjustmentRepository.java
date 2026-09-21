package com.transformersas.marketplace.inventory.infrastructure.persistence.repository;

import com.transformersas.marketplace.inventory.domain.repository.StockAdjustmentRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Adaptador JDBC sobre products.stock. El incremento lo resuelve la BD en una sola sentencia (sin carreras). */
@Repository
public class JdbcStockAdjustmentRepository implements StockAdjustmentRepository {

    private final JdbcClient jdbc;

    public JdbcStockAdjustmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean increase(Long productId, int quantity) {
        return jdbc.sql("UPDATE products SET stock = stock + ? WHERE id = ?").params(quantity, productId).update() == 1;
    }
}
