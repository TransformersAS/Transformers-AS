package com.transformersas.marketplace.reports.domain.model;

/**
 * Decisiones de moderación (RF-159). Sancionar una cuenta no es una decisión de este caso de uso:
 * se remite a CU-22 mediante una remisión.
 */
public enum ModerationDecision {
    MANTENER,
    OCULTAR_TEMPORALMENTE,
    RETIRAR
}
