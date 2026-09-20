package com.transformersas.marketplace.product.dto;

import com.transformersas.marketplace.product.Product;
import com.transformersas.marketplace.product.ProductStatus;

import java.math.BigDecimal;
import java.util.List;

/** Publicación tal como la ve su vendedor; sirve también como vista previa antes de publicar. */
public record SellerProductResponse(Long id, String name, String description, BigDecimal price, Integer stock,
                                    String category, Long brandId, ProductStatus status, List<String> imageUrls,
                                    List<Long> attributeValueIds, List<VariantResponse> variants) {

    public record VariantResponse(String name, BigDecimal price, Integer stock) {
    }

    public static SellerProductResponse from(Product product) {
        return new SellerProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStock(), product.getCategory(), product.getBrandId(),
                product.getStatus(), List.copyOf(product.getImageUrls()),
                product.getAttributeValueIds().stream().sorted().toList(),
                product.getVariants().stream()
                        .map(variant -> new VariantResponse(variant.getName(), variant.getPrice(), variant.getStock()))
                        .toList());
    }
}
