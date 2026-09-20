package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.domain.model.StorePolicy;
import com.transformersas.marketplace.stores.domain.model.StoreProfile;
import com.transformersas.marketplace.stores.domain.repository.StorePolicyRules;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Reglas que dependen de otras tiendas o del marketplace, compartidas por la vista previa y el guardado para que
 * ambos respondan igual: nombre no repetido (A1) y política que respete los mínimos (A5). La forma de cada dato
 * (A2, A3) ya la garantizan StoreProfile y StorePolicy.
 */
@Component
class StoreSettingsValidator {

    private final StoreRepository stores;
    private final StorePolicyRules rules;

    StoreSettingsValidator(StoreRepository stores, StorePolicyRules rules) {
        this.stores = stores;
        this.rules = rules;
    }

    void validate(Long storeId, StoreProfile profile, StorePolicy policy) {
        if (stores.existsByName(profile.name(), storeId)) {
            throw BusinessException.conflict("STORE_NAME_TAKEN", "Ya existe otra tienda con ese nombre");
        }
        int minimum = rules.minReturnWindowDays();
        if (policy.returnWindowDays() < minimum) {
            throw new BusinessException(BusinessException.Kind.INVALID, "STORE_POLICY_BELOW_MINIMUM",
                    "El plazo de devolución no puede ser menor a " + minimum + " días (regla del marketplace)",
                    Map.of("minReturnWindowDays", minimum));
        }
    }
}
