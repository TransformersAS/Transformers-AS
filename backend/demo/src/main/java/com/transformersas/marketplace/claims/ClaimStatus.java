package com.transformersas.marketplace.claims;

/** Estado de una reclamación (CU-13). */
public enum ClaimStatus {
    /** El comprador la abrió; espera al vendedor. */
    OPEN,
    /** El vendedor pidió más información y espera al comprador. */
    INFO_REQUESTED,
    /** El vendedor propuso una solución y espera la respuesta del comprador. */
    SOLUTION_PROPOSED,
    /** No hubo acuerdo: la revisa un agente de soporte. */
    ESCALATED,
    /** Terminó, ya sea por acuerdo o por decisión de soporte. */
    RESOLVED
}
