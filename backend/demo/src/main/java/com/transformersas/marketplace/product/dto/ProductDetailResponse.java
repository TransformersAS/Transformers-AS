package com.transformersas.marketplace.product.dto;

import java.math.BigDecimal;
import java.util.List;

/** Informacion publica de una publicacion; no expone datos privados del vendedor. */
public record ProductDetailResponse(Long id, String name, String description, BigDecimal price,
        Integer stock, String category, Boolean active, String brand, StoreSummary store,
        List<String> imageUrls, List<Specification> specifications, List<Variant> variants) {
    public record StoreSummary(Long id, String name, String description) {}
    public record Specification(String name, String value) {}
    public record Variant(String name, BigDecimal price, Integer stock) {}
}
