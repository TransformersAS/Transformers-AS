package com.transformersas.marketplace.stock.dto;

import com.transformersas.marketplace.stock.StockMovement;
import com.transformersas.marketplace.stock.StockMovementType;

import java.time.LocalDateTime;

public record StockMovementResponse(Long id, StockMovementType type, int quantity, int stockAfter, String reason,
                                    LocalDateTime createdAt) {

    public static StockMovementResponse from(StockMovement movement) {
        return new StockMovementResponse(movement.getId(), movement.getType(), movement.getQuantity(),
                movement.getStockAfter(), movement.getReason(), movement.getCreatedAt());
    }
}
