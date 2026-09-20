package com.transformersas.marketplace.logistics.application.dto;

import java.time.Duration;

/**
 * Ritmo de las consultas al servicio logístico. minRefreshInterval limita las consultas pedidas por un usuario;
 * pollInterval es cada cuánto se vuelve a consultar un envío activo; batchSize acota el trabajo de cada barrido.
 */
public record TrackingPolicy(Duration minRefreshInterval, Duration pollInterval, int batchSize) {

    public TrackingPolicy {
        if (minRefreshInterval == null || minRefreshInterval.isNegative()
                || pollInterval == null || pollInterval.isNegative() || batchSize < 1) {
            throw new IllegalArgumentException("Política de seguimiento inválida");
        }
    }
}
