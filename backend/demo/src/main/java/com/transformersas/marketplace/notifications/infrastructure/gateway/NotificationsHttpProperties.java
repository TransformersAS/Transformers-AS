package com.transformersas.marketplace.notifications.infrastructure.gateway;

import com.transformersas.marketplace.shared.http.ExternalRestClients;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Configuración del adaptador HTTP de notificaciones (notifications.provider=http). Mismas reglas que logística. */
@ConfigurationProperties(prefix = "notifications.http")
public record NotificationsHttpProperties(
        String baseUrl,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("true") boolean requireHttps
) {
    public NotificationsHttpProperties {
        ExternalRestClients.requireValidTimeout(timeout, "notifications.http.timeout");
    }
}
