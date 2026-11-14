package com.transformersas.marketplace.suspensions.application.dto;

import com.transformersas.marketplace.suspensions.domain.model.PendingOperations;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resumen que el administrador confirma antes de suspender (paso 12): fechas, qué se bloquea, qué sigue permitido, lo
 * que la tienda tiene en curso y un aviso si ya tiene otra restricción.
 */
public record SuspensionPreview(Long storeId, String storeName, String reason, int durationDays,
                                LocalDateTime startsAt, LocalDateTime endsAt, List<String> blocked,
                                List<String> allowed, PendingOperations pending, String warning) {
}
