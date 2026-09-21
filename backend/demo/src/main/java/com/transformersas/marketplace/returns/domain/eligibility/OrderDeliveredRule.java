package com.transformersas.marketplace.returns.domain.eligibility;

import java.util.Optional;

/** Solo se devuelve lo de un pedido ya entregado. */
public final class OrderDeliveredRule implements ReturnEligibilityRule {

    @Override
    public Optional<Ineligibility> check(ReturnCandidate candidate) {
        if (candidate.order().delivered()) {
            return Optional.empty();
        }
        return Optional.of(new Ineligibility("RETURN_ORDER_NOT_DELIVERED",
                "Solo se pueden devolver productos de pedidos ya entregados"));
    }
}
