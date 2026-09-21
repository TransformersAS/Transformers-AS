package com.transformersas.marketplace.returns.domain.model;

import java.time.LocalDateTime;

/** Solicitud de información del vendedor al comprador. Solo hay una abierta a la vez (RF-052, RF-107). */
public record InformationRequest(Long id, String message, Long requestedByAccountId, LocalDateTime requestedAt,
                                 LocalDateTime dueAt) {

    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(dueAt);
    }
}
