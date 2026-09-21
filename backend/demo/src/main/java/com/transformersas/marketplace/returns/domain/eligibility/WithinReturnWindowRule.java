package com.transformersas.marketplace.returns.domain.eligibility;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Dentro del plazo de devolución de la tienda, contado desde la entrega. Se puede pedir hasta el instante en que se
 * cumplen los días. Si el pedido consta como entregado pero no hay fecha de entrega, no se puede afirmar que esté a
 * tiempo y se rechaza con un motivo propio, sin inventar una fecha.
 */
public final class WithinReturnWindowRule implements ReturnEligibilityRule {

    @Override
    public Optional<Ineligibility> check(ReturnCandidate candidate) {
        LocalDateTime deliveredAt = candidate.order().deliveredAt();
        if (deliveredAt == null) {
            return Optional.of(new Ineligibility("RETURN_DELIVERY_DATE_UNKNOWN",
                    "No se pudo determinar la fecha de entrega del pedido"));
        }
        LocalDateTime lastMoment = deliveredAt.plusDays(candidate.returnWindowDays());
        if (candidate.now().isAfter(lastMoment)) {
            return Optional.of(new Ineligibility("RETURN_WINDOW_EXPIRED",
                    "El plazo de " + candidate.returnWindowDays() + " días para devolver este producto terminó el "
                            + lastMoment.toLocalDate()));
        }
        return Optional.empty();
    }
}
