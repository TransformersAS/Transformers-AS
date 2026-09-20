package com.transformersas.marketplace.stores.domain.repository;

/**
 * Reglas obligatorias del marketplace contra las que se validan las políticas de cada tienda (A5). Se configuran
 * (stores.policy.*) porque las reglas de reclamaciones y devoluciones aún no tienen un módulo propio.
 */
public interface StorePolicyRules {

    /** Plazo de devolución mínimo, en días, que toda tienda debe ofrecer. */
    int minReturnWindowDays();
}
