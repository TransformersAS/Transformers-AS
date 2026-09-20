package com.transformersas.marketplace.notifications.infrastructure.gateway;

import com.transformersas.marketplace.notifications.domain.repository.ExternalNotificationGateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Selecciona el adaptador según notifications.provider: simulated (por defecto) o http. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationsHttpProperties.class)
public class NotificationsConfiguration {

    @Bean
    @ConditionalOnProperty(name = "notifications.provider", havingValue = "http")
    ExternalNotificationGateway httpExternalNotificationGateway(NotificationsHttpProperties properties,
                                                                CircuitBreakerRegistry registry) {
        return HttpExternalNotificationGateway.create(properties, RestClient.builder(), registry);
    }
}
