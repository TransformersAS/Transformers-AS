package com.transformersas.marketplace.shared.http;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Construcción común de clientes HTTP hacia servicios externos: timeout de conexión y respuesta con tope de 10 s
 * (RNF-042) y HTTPS obligatorio salvo pruebas locales (RNF-001). Sin redirecciones ni reintentos automáticos.
 */
public final class ExternalRestClients {

    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(10);

    private ExternalRestClients() {
    }

    /** Valida el timeout configurado (entre 1 ms y 10 s). property solo sirve para el mensaje de error. */
    public static Duration requireValidTimeout(Duration timeout, String property) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(property + " debe estar entre 1 ms y 10 s (RNF-042)");
        }
        return timeout;
    }

    public static RestClient build(RestClient.Builder builder, String baseUrl, Duration timeout, boolean requireHttps,
                                   String propertyPrefix) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(propertyPrefix + ".base-url es obligatorio con el proveedor http");
        }
        if (requireHttps && !"https".equalsIgnoreCase(URI.create(baseUrl).getScheme())) {
            throw new IllegalStateException("El servicio externo debe usar HTTPS (RNF-001): " + propertyPrefix);
        }
        // HTTP/1.1 explícito: el cliente JDK intentaría negociar h2c en claro y varios servidores lo cancelan.
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
