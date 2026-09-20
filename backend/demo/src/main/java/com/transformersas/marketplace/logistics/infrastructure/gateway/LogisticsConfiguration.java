package com.transformersas.marketplace.logistics.infrastructure.gateway;

import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Selecciona el adaptador según logistics.provider: simulated (por defecto) o http. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LogisticsHttpProperties.class)
public class LogisticsConfiguration {

    @Bean
    @ConditionalOnProperty(name = "logistics.provider", havingValue = "http")
    LogisticsGateway httpLogisticsGateway(LogisticsHttpProperties properties, CircuitBreakerRegistry registry) {
        return HttpLogisticsGateway.create(properties, RestClient.builder(), registry);
    }
}
