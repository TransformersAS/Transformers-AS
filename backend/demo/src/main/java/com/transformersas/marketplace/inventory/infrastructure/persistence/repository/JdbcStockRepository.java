package com.transformersas.marketplace.inventory.infrastructure.persistence.repository;

import com.transformersas.marketplace.inventory.domain.repository.StockRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Adaptador JDBC sobre la tabla products: lee y modifica solo la columna stock, sin usar la entidad de otro módulo. */
@Repository
public class JdbcStockRepository implements StockRepository {

    private final JdbcClient jdbc;

    public JdbcStockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, Integer> findStock(Collection<Long> productIds) {
        Map<Long, Integer> result = new HashMap<>();
        jdbc.sql("SELECT id, stock FROM products WHERE id IN (:ids)").param("ids", productIds)
                .query((rs, row) -> {
                    result.put(rs.getLong("id"), rs.getInt("stock"));
                    return null;
                }).list();
        return result;
    }
}
