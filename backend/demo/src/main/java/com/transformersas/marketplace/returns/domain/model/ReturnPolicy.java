package com.transformersas.marketplace.returns.domain.model;

import java.time.Duration;

/**
 * Plazos y topes de las devoluciones. Los valores por defecto son supuestos documentados, no requisitos: 24 h de
 * información y de inspección salen del caso de uso; el resto se configura con returns.*.
 *
 * @param sellerDecisionDeadline  desde que se solicita, cuándo se marca "atrasada" (no decide nada por sí sola)
 * @param informationWindow       plazo del comprador para responder una solicitud de información
 * @param inspectionWindow        ventana del vendedor para reportar un problema tras recibir la mercancía
 * @param methodSelectionOverdueAfter desde que se aprueba, cuándo se marca "atrasada" la elección del método de retorno
 * @param refundMaxAttempts       intentos automáticos del reembolso antes de dejarlo para revisión manual
 * @param refundRetryDelay        espera entre reintentos del reembolso
 */
public record ReturnPolicy(Duration sellerDecisionDeadline, Duration informationWindow, Duration inspectionWindow,
                           Duration methodSelectionOverdueAfter, int refundMaxAttempts, Duration refundRetryDelay) {

    public static final ReturnPolicy DEFAULT = new ReturnPolicy(Duration.ofHours(72), Duration.ofHours(24),
            Duration.ofHours(24), Duration.ofHours(72), 5, Duration.ofMinutes(15));

    public ReturnPolicy {
        for (Duration duration : new Duration[]{sellerDecisionDeadline, informationWindow, inspectionWindow,
                methodSelectionOverdueAfter, refundRetryDelay}) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("Los plazos de devolución deben ser positivos");
            }
        }
        if (refundMaxAttempts < 1) {
            throw new IllegalArgumentException("Debe haber al menos un intento de reembolso");
        }
    }
}
