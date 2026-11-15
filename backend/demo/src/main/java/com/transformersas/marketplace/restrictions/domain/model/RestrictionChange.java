package com.transformersas.marketplace.restrictions.domain.model;

import java.time.LocalDateTime;

/**
 * Un hecho en la historia de una restricción: su aplicación, un cambio de duración o de permanencia, su retiro
 * anticipado o su fin automático. actorId es nulo cuando lo hizo el sistema.
 */
public record RestrictionChange(Long id, Long restrictionId, Type type, LocalDateTime previousEndsAt,
                                LocalDateTime newEndsAt, String justification, Long actorId,
                                LocalDateTime createdAt) {

    public enum Type { APPLIED, DURATION_CHANGED, MADE_PERMANENT, MADE_TEMPORARY, WITHDRAWN, EXPIRED }
}
