package com.transformersas.marketplace.returns.domain.eligibility;

import java.util.Optional;

/**
 * Una regla que puede impedir una devolución. Las reglas objetivas del caso de uso (línea del pedido, pedido entregado,
 * dentro del plazo) son fijas; las "reglas generales del marketplace" no están definidas, así que se enchufan: basta con
 * declarar un bean de esta interfaz para que se aplique después de las objetivas. Por defecto no hay ninguna.
 */
public interface ReturnEligibilityRule {

    /** Vacío si la línea pasa la regla; si no, el motivo por el que no. */
    Optional<Ineligibility> check(ReturnCandidate candidate);
}
