package com.transformersas.marketplace.claims;

/** Cómo terminó una reclamación. */
public enum ClaimResolution {
    /** El comprador aceptó la solución que propuso el vendedor. */
    SOLUTION_ACCEPTED,
    /** Soporte decidió reembolsar (total o parcialmente) al comprador. */
    REFUND_GRANTED,
    /** Soporte decidió que la reclamación no procede. */
    REJECTED
}
