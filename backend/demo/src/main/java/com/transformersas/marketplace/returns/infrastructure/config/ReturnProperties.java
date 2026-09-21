package com.transformersas.marketplace.returns.infrastructure.config;

import com.transformersas.marketplace.returns.domain.model.ReturnPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Configuración de las devoluciones (CU-19), con {@code returns.*}. Los valores por defecto son supuestos documentados
 * salvo las 24 h de información e inspección, que salen del caso de uso.
 *
 * @param sellerDecisionDeadline      tras cuánto se marca "atrasada" la decisión del vendedor (72 h, supuesto)
 * @param informationWindow           plazo del comprador para responder una solicitud de información (24 h)
 * @param inspectionWindow            ventana del vendedor para reportar un problema (24 h)
 * @param methodSelectionOverdueAfter tras cuánto se marca "atrasada" la elección del método de retorno (72 h, supuesto)
 * @param refundMaxAttempts           intentos automáticos del reembolso antes de dejarlo para revisión manual
 * @param refundRetryDelay            espera entre reintentos del reembolso
 * @param evidence                    límites de las imágenes de la solicitud
 * @param sweep                       el barrido que vence inspecciones y reintenta reembolsos
 */
@ConfigurationProperties("returns")
public record ReturnProperties(@DefaultValue("72h") Duration sellerDecisionDeadline,
                               @DefaultValue("24h") Duration informationWindow,
                               @DefaultValue("24h") Duration inspectionWindow,
                               @DefaultValue("72h") Duration methodSelectionOverdueAfter,
                               @DefaultValue("5") int refundMaxAttempts,
                               @DefaultValue("15m") Duration refundRetryDelay,
                               @DefaultValue Evidence evidence, @DefaultValue Sweep sweep) {

    public record Evidence(@DefaultValue("3") int maxCount, @DefaultValue("5MB") DataSize maxSize) {
    }

    /** {@code interval}: cada cuánto corre; {@code batchSize}: cuántas devoluciones toma por vuelta. */
    public record Sweep(@DefaultValue("true") boolean enabled, @DefaultValue("1m") Duration interval,
                        @DefaultValue("20") int batchSize) {
    }

    public ReturnPolicy policy() {
        return new ReturnPolicy(sellerDecisionDeadline, informationWindow, inspectionWindow,
                methodSelectionOverdueAfter, refundMaxAttempts, refundRetryDelay);
    }
}
