package com.transformersas.marketplace.orders.domain.model;

import com.transformersas.marketplace.shared.error.BusinessException;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados del pedido y reglas de transición centralizadas (D5). Los estados logísticos existen para CU-24;
 * en esta entrega ninguna transición sale ni llega a ellos.
 */
public enum OrderStatus {
    CONFIRMED,
    IN_PREPARATION,
    READY_FOR_DISPATCH,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    DELIVERY_EXCEPTION,
    DELIVERY_ATTEMPT_FAILED,
    RETURNED_TO_SELLER,
    CANCELLED,
    /** El comprador pidió cancelar (CU-11). Aún no hay transiciones desde aquí ni hacia otros estados. */
    CANCELLATION_REQUESTED;

    /** Destinos válidos desde este estado. */
    public Set<OrderStatus> allowedTargets() {
        return switch (this) {
            case CONFIRMED -> EnumSet.of(IN_PREPARATION, CANCELLED);
            case IN_PREPARATION -> EnumSet.of(READY_FOR_DISPATCH, CANCELLED);
            default -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTargets().contains(target);
    }

    /** Lanza 409 con código estable si la transición no está permitida. */
    public void requireTransitionTo(OrderStatus target) {
        if (!canTransitionTo(target)) {
            throw BusinessException.conflict("ORDER_INVALID_TRANSITION",
                    "El pedido en estado " + this + " no puede pasar a " + target);
        }
    }

    /** Las novedades de preparación solo se registran antes de que el pedido quede listo (RF-121). */
    public boolean allowsPreparationIssues() {
        return this == CONFIRMED || this == IN_PREPARATION;
    }
}
