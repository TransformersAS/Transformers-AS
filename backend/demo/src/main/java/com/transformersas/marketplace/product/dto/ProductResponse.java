package com.transformersas.marketplace.product.dto;

import com.transformersas.marketplace.product.Product;
import java.math.BigDecimal;

public record ProductResponse(Long id, String name, String description, BigDecimal price,
                              Integer stock, String category, Boolean active) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStock(), product.getCategory(), product.getActive());
    }
}
