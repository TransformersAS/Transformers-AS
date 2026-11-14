package com.transformersas.marketplace.promotions.dto;

import com.transformersas.marketplace.promotions.DiscountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Un cupón como lo ve el vendedor (CU-16). status resume el estado: INACTIVE (apagado), SCHEDULED (activo, aún no
 * empieza), RUNNING (se puede usar ahora) o EXPIRED (ya terminó). conflicts son los otros cupones activos de la tienda
 * que se cruzan en fechas y productos: es un aviso, no impide guardar.
 */
public record PromotionResponse(
        Long id,
        String name,
        String code,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal minPurchase,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        boolean active,
        String status,
        List<Long> productIds,
        List<String> conflicts
) {
}
