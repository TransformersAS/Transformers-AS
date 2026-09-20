package com.transformersas.marketplace.stores.infrastructure.config;

import com.transformersas.marketplace.stores.domain.repository.StorePolicyRules;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reglas obligatorias del marketplace para las políticas de tienda (stores.policy.*). El esquema exige como mínimo 30
 * días (chk_stores_return_window), por eso un valor menor impide el arranque.
 */
@ConfigurationProperties(prefix = "stores.policy")
public record StorePolicyProperties(@DefaultValue("30") int minReturnWindowDays) implements StorePolicyRules {

    public StorePolicyProperties {
        if (minReturnWindowDays < 30) {
            throw new IllegalArgumentException("stores.policy.min-return-window-days debe ser al menos 30");
        }
    }
}
