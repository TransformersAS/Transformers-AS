package com.transformersas.marketplace.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository
    extends JpaRepository<Product, Long> {

    List<Product>
        findByActiveTrueAndStockGreaterThan(
            Integer stock
        );
}