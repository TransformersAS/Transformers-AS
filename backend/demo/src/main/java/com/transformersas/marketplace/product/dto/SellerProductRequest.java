package com.transformersas.marketplace.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

/** Datos que el vendedor envía para crear o editar una publicación (CU-14). */
public record SellerProductRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String description,
        @NotNull @DecimalMin("0") @Digits(integer = 36, fraction = 2) BigDecimal price,
        @NotNull @Min(0) Integer stock,
        @NotBlank @Size(max = 255) String category,
        Long brandId,
        List<@NotBlank @Size(max = 500) String> imageUrls,
        List<@NotNull Long> attributeValueIds,
        List<@Valid VariantRequest> variants
) {

    public record VariantRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal price,
            @NotNull @Min(0) Integer stock
    ) {
    }
}
