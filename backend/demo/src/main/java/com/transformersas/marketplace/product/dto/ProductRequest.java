package com.transformersas.marketplace.product.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String description,
        @NotNull @DecimalMin("0") @Digits(integer = 36, fraction = 2) BigDecimal price,
        @NotNull @Min(0) Integer stock,
        @NotBlank @Size(max = 255) String category,
        Boolean active
) {}
