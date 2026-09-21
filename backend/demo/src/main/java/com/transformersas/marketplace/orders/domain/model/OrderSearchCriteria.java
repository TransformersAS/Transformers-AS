package com.transformersas.marketplace.orders.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Criterios de búsqueda de pedidos de UNA tienda (RF-111). createdFrom es inclusivo y createdBefore exclusivo.
 * Los filtros nulos no se aplican; statuses vacío tampoco.
 */
public record OrderSearchCriteria(
        Long storeId,
        Set<OrderStatus> statuses,
        LocalDateTime createdFrom,
        LocalDateTime createdBefore,
        Long orderId,
        int page,
        int size
) {
    public static final int MAX_SIZE = 100;

    public OrderSearchCriteria {
        if (storeId == null) {
            throw new IllegalArgumentException("La tienda es obligatoria");
        }
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw BusinessException.invalid("INVALID_PAGINATION",
                    "page debe ser >= 0 y size debe estar entre 1 y " + MAX_SIZE);
        }
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    }
}
