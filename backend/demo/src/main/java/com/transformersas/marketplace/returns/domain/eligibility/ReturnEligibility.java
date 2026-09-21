package com.transformersas.marketplace.returns.domain.eligibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evalúa si una línea se puede devolver (A1): primero las reglas objetivas, en orden, y después las del marketplace
 * que se hayan enchufado. Devuelve el primer motivo que encuentre. Sin reglas del marketplace solo rigen las objetivas.
 */
public final class ReturnEligibility {
    private final List<ReturnEligibilityRule> rules = new ArrayList<>();

    public ReturnEligibility(List<ReturnEligibilityRule> marketplaceRules) {
        rules.add(new LineBelongsToOrderRule());
        rules.add(new OrderDeliveredRule());
        rules.add(new WithinReturnWindowRule());
        rules.addAll(marketplaceRules);
    }

    public Optional<Ineligibility> firstFailure(ReturnCandidate candidate) {
        for (ReturnEligibilityRule rule : rules) {
            Optional<Ineligibility> failure = rule.check(candidate);
            if (failure.isPresent()) {
                return failure;
            }
        }
        return Optional.empty();
    }

    public boolean isEligible(ReturnCandidate candidate) {
        return firstFailure(candidate).isEmpty();
    }
}
