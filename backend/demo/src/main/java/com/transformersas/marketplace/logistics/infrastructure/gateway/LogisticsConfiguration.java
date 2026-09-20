package com.transformersas.marketplace.logistics.infrastructure.gateway;

import com.transformersas.marketplace.logistics.application.dto.TrackingPolicy;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Selecciona el adaptador según logistics.provider: simulated (por defecto) o http, y fija el ritmo de las consultas
 * de seguimiento (logistics.tracking.*).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LogisticsHttpProperties.class)
public class LogisticsConfiguration {

    @Bean
    @ConditionalOnProperty(name = "logistics.provider", havingValue = "http")
    LogisticsGateway httpLogisticsGateway(LogisticsHttpProperties properties, CircuitBreakerRegistry registry) {
        return HttpLogisticsGateway.create(properties, RestClient.builder(), registry);
    }

    @Bean
    TrackingPolicy trackingPolicy(
            @Value("${logistics.tracking.refresh-min-interval:5s}") Duration refreshMinInterval,
            @Value("${logistics.tracking.polling-interval:30s}") Duration pollingInterval,
            @Value("${logistics.tracking.polling-batch-size:20}") int batchSize) {
        return new TrackingPolicy(refreshMinInterval, pollingInterval, batchSize);
    }
}
