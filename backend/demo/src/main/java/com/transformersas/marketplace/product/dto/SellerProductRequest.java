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
        List<@Valid VariantRequest> variants,
        // CU-26: costo unitario interno, opcional; sin él la analítica no puede calcular la ganancia de este producto.
        @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal unitCost
) {

    /** Solicitud sin costo unitario, como la arman la importación por Excel (CU-15) y los clientes previos a CU-26. */
    public SellerProductRequest(String name, String description, BigDecimal price, Integer stock, String category,
                                Long brandId, List<String> imageUrls, List<Long> attributeValueIds,
                                List<VariantRequest> variants) {
        this(name, description, price, stock, category, brandId, imageUrls, attributeValueIds, variants, null);
    }

    public record VariantRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal price,
            @NotNull @Min(0) Integer stock
    ) {
    }
}
