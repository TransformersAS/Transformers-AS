package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.domain.model.LogisticsRejectedException;
import com.transformersas.marketplace.logistics.domain.model.LogisticsUnavailableException;
import com.transformersas.marketplace.logistics.domain.repository.LogisticsGateway;
import com.transformersas.marketplace.logistics.infrastructure.gateway.HttpLogisticsGateway;
import com.transformersas.marketplace.logistics.infrastructure.gateway.LogisticsHttpProperties;
import com.transformersas.marketplace.support.AbstractIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** logistics.provider=http selecciona el adaptador HTTP; el Circuit Breaker "logistics" sale de application.properties. */
@TestPropertySource(properties = {
        "logistics.provider=http",
        "logistics.http.base-url=http://localhost:1",
        "logistics.http.require-https=false"})
class LogisticsProviderSelectionTests extends AbstractIntegrationTest {

    @Autowired LogisticsGateway gateway;
    @Autowired LogisticsHttpProperties properties;
    @Autowired CircuitBreakerRegistry registry;

    @Test
    void httpProviderIsSelectedAndTheDefaultTimeoutIsTenSeconds() {
        assertThat(gateway).isInstanceOf(HttpLogisticsGateway.class);
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void circuitBreakerInstanceIsConfiguredFromProperties() {
        CircuitBreakerConfig config = registry.circuitBreaker("logistics").getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        // Solo los fallos temporales cuentan; el rechazo definitivo (4xx) se ignora.
        assertThat(config.getRecordExceptionPredicate().test(new LogisticsUnavailableException("x", null))).isTrue();
        assertThat(config.getRecordExceptionPredicate().test(new LogisticsRejectedException("x", null))).isFalse();
        assertThat(config.getIgnoreExceptionPredicate().test(new LogisticsRejectedException("x", null))).isTrue();
    }
}
