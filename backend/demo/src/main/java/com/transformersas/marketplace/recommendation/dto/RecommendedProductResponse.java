package com.transformersas.marketplace.recommendation.dto;

import com.transformersas.marketplace.product.Product;

import java.math.BigDecimal;

public record RecommendedProductResponse(

    Long id,

    String name,

    String description,

    BigDecimal price,

    Integer stock,

    String category

) {

    public static RecommendedProductResponse from(
        Product product
    ) {

        return new RecommendedProductResponse(

            product.getId(),

            product.getName(),

            product.getDescription(),

            product.getPrice(),

            product.getStock(),

            product.getCategory()
        );
    }
}