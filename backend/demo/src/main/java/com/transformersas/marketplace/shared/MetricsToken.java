package com.transformersas.marketplace.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Comprueba el token con el que Prometheus lee las métricas (cabecera "Authorization: Bearer token"). */
final class MetricsToken {

    private static final String PREFIX = "Bearer ";

    private MetricsToken() {
    }

    /** Sin token configurado nadie entra. La comparación tarda lo mismo acierte o no, para no dar pistas. */
    static boolean matches(String configured, String authorizationHeader) {
        if (configured == null || configured.isBlank() || authorizationHeader == null
                || !authorizationHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = configured.strip().getBytes(StandardCharsets.UTF_8);
        byte[] received = authorizationHeader.substring(PREFIX.length()).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, received);
    }
}
