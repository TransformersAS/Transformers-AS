package com.transformersas.marketplace.promotions.dto;

import com.transformersas.marketplace.promotions.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Datos de un cupón al crearlo o editarlo (CU-16). El código admite letras, números, guion y guion bajo; se guarda en
 * mayúsculas. productIds vacío o ausente significa "toda la tienda".
 */
public record PromotionRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,30}$",
                message = "El código debe tener de 3 a 30 letras, números, guiones o guiones bajos") String code,
        @NotNull DiscountType discountType,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal discountValue,
        @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal minPurchase,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt,
        Set<Long> productIds
) {
}
