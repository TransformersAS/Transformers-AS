package com.transformersas.marketplace.product.dto;

import com.transformersas.marketplace.product.Product;
import java.math.BigDecimal;

public record ProductResponse(Long id, String name, String description, BigDecimal price,
                              Integer stock, String category, Boolean active, double ratingAverage, long reviewCount) {
    /** Sin calificaciones todavía: lo usa la consulta del catálogo, que solo trae las columnas del listado. */
    public ProductResponse(Long id, String name, String description, BigDecimal price,
                           Integer stock, String category, Boolean active) {
        this(id, name, description, price, stock, category, active, 0, 0);
    }

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStock(), product.getCategory(), product.getActive(),0,0);
    }
    public ProductResponse withRating(double average,long count) {return new ProductResponse(id,name,description,price,stock,category,active,average,count);}
}
