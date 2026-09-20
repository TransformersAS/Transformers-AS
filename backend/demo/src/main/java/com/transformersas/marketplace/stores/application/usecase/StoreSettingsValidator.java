package com.transformersas.marketplace.stores.application.usecase;

import com.transformersas.marketplace.logistics.domain.repository.ShippingMethodCatalog;
import com.transformersas.marketplace.shared.error.BusinessException;
import com.transformersas.marketplace.stores.application.dto.UpdateStoreSettingsCommand;
import com.transformersas.marketplace.stores.domain.repository.StorePolicyRules;
import com.transformersas.marketplace.stores.domain.repository.StoreRepository;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Reglas que dependen de otras tiendas o del marketplace, compartidas por la vista previa y el guardado para que
 * ambos respondan igual: nombre no repetido (A1), política que respete los mínimos (A5) y métodos de envío que el
 * marketplace ofrece (A6). La forma de cada dato (A2, A3) ya la garantizan StoreProfile y StorePolicy.
 */
@Component
class StoreSettingsValidator {

    private final StoreRepository stores;
    private final StorePolicyRules rules;
    private final ShippingMethodCatalog shippingMethods;

    StoreSettingsValidator(StoreRepository stores, StorePolicyRules rules, ShippingMethodCatalog shippingMethods) {
        this.stores = stores;
        this.rules = rules;
        this.shippingMethods = shippingMethods;
    }

    void validate(UpdateStoreSettingsCommand command) {
        if (stores.existsByName(command.profile().name(), command.storeId())) {
            throw BusinessException.conflict("STORE_NAME_TAKEN", "Ya existe otra tienda con ese nombre");
        }
        int minimum = rules.minReturnWindowDays();
        if (command.policy().returnWindowDays() < minimum) {
            throw new BusinessException(BusinessException.Kind.INVALID, "STORE_POLICY_BELOW_MINIMUM",
                    "El plazo de devolución no puede ser menor a " + minimum + " días (regla del marketplace)",
                    Map.of("minReturnWindowDays", minimum));
        }
        if (command.shippingMethods().isEmpty()) {
            throw BusinessException.invalid("STORE_SHIPPING_METHODS_REQUIRED",
                    "La tienda debe habilitar al menos un método de envío");
        }
        List<String> available = shippingMethods.availableMethods();
        List<String> unavailable = command.shippingMethods().stream().filter(method -> !available.contains(method))
                .toList();
        if (!unavailable.isEmpty()) {
            throw new BusinessException(BusinessException.Kind.INVALID, "STORE_SHIPPING_METHOD_UNAVAILABLE",
                    "Hay métodos de envío que el marketplace no ofrece: " + String.join(", ", unavailable),
                    Map.of("unavailable", unavailable, "available", available));
        }
    }
}
