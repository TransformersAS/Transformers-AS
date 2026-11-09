package com.transformersas.marketplace.suspensions.domain.model;

import java.time.LocalDateTime;

/**
 * Un hecho en la historia de una suspensión (RF-172): su aplicación, un cambio de duración, su levantamiento o su fin
 * automático. actorId es nulo cuando lo hizo el sistema. reactivationBlocked indica que al terminar la tienda no se
 * reactivó porque tenía otra restricción vigente (A6).
 */
public record SuspensionChange(Long id, Long suspensionId, Type type, LocalDateTime previousEndsAt,
                               LocalDateTime newEndsAt, String justification, Long actorId,
                               boolean reactivationBlocked, LocalDateTime createdAt) {

    public enum Type { APPLIED, DURATION_CHANGED, LIFTED, EXPIRED }
}
