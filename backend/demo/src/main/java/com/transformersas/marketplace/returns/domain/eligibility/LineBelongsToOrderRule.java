package com.transformersas.marketplace.returns.domain.eligibility;

import java.util.Optional;

/** La línea que se devuelve pertenece al pedido. */
public final class LineBelongsToOrderRule implements ReturnEligibilityRule {

    @Override
    public Optional<Ineligibility> check(ReturnCandidate candidate) {
        if (candidate.line().isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new Ineligibility("RETURN_LINE_NOT_IN_ORDER", "El producto no pertenece a ese pedido"));
    }
}
