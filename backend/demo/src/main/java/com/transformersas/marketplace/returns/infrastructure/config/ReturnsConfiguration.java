package com.transformersas.marketplace.returns.infrastructure.config;

import com.transformersas.marketplace.returns.domain.eligibility.ReturnEligibility;
import com.transformersas.marketplace.returns.domain.eligibility.ReturnEligibilityRule;
import com.transformersas.marketplace.returns.domain.model.ReturnPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cablea las devoluciones: la política de plazos y la elegibilidad con las reglas del marketplace que existan. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReturnProperties.class)
class ReturnsConfiguration {

    @Bean
    ReturnPolicy returnPolicy(ReturnProperties properties) {
        return properties.policy();
    }

    /** Reglas objetivas más las que se declaren como bean de {@link ReturnEligibilityRule}; por defecto ninguna. */
    @Bean
    ReturnEligibility returnEligibility(ObjectProvider<ReturnEligibilityRule> marketplaceRules) {
        return new ReturnEligibility(marketplaceRules.orderedStream().toList());
    }
}
