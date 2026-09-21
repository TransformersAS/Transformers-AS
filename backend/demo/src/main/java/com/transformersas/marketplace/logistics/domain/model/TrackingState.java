package com.transformersas.marketplace.logistics.domain.model;

import java.time.LocalDateTime;

/**
 * Control de consulta de un envío o de una devolución. active deja de serlo al llegar a un estado final; pollFailures
 * mayor que 0 indica que la última consulta falló y lo que se muestra es el último seguimiento conocido (A7).
 */
public record TrackingState(Long subjectId, boolean active, LocalDateTime lastPolledAt, int pollFailures) {

    public boolean lastPollFailed() {
        return pollFailures > 0;
    }
}
