package com.transformersas.marketplace.returns.domain.model;

import com.transformersas.marketplace.shared.audit.ActorType;

/**
 * Un hecho de la línea de tiempo, producido por una transición del agregado. La capa de aplicación lo guarda en
 * return_events y en la auditoría. from y to son nulos cuando el hecho no cambia el estado; actorId es nulo para el
 * sistema.
 */
public record ReturnEvent(ReturnEventType type, ReturnStatus from, ReturnStatus to, ActorType actorType, Long actorId,
                          String details) {
}
