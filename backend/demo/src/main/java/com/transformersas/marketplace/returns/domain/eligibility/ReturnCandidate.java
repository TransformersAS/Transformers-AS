package com.transformersas.marketplace.returns.domain.eligibility;

import com.transformersas.marketplace.returns.domain.model.ReturnLine;
import com.transformersas.marketplace.returns.domain.port.OrderForReturn;

import java.time.LocalDateTime;
import java.util.Optional;

/** Lo que las reglas de elegibilidad evalúan: la línea que se quiere devolver, su pedido y el plazo de la tienda. */
public record ReturnCandidate(OrderForReturn order, Long orderItemId, int returnWindowDays, LocalDateTime now) {

    public Optional<ReturnLine> line() {
        return order.line(orderItemId);
    }
}
