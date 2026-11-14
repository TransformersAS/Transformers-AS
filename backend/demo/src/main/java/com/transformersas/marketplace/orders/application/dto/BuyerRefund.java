package com.transformersas.marketplace.orders.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Datos funcionales: nunca expone claves, actores ni errores del proveedor. */
public record BuyerRefund(String reason, BigDecimal amount, String currency, String status,
        String label, String message, LocalDateTime requestedAt, LocalDateTime updatedAt,
        LocalDateTime confirmedAt) { }
