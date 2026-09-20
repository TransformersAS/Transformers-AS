package com.transformersas.marketplace.logistics.infrastructure.gateway;

import com.transformersas.marketplace.shared.http.ExternalRestClients;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración del adaptador HTTP de logística (logistics.provider=http).
 * timeout aplica a la conexión y a la respuesta: RNF-042 fija 10 s como máximo, por eso no se admite más.
 * requireHttps (RNF-001) solo se desactiva en pruebas locales con un servidor simulado.
 */
@ConfigurationProperties(prefix = "logistics.http")
public record LogisticsHttpProperties(
        String baseUrl,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("true") boolean requireHttps
) {
    public LogisticsHttpProperties {
        ExternalRestClients.requireValidTimeout(timeout, "logistics.http.timeout");
    }
}
