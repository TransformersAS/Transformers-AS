package com.transformersas.marketplace.reservation.dto;

import java.time.LocalDateTime;

public record ReservationResponse(
        Long id,
        Long productId,
        String productName,
        Integer quantity,
        String status,
        LocalDateTime expiresAt
) {
}
