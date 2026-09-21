package com.transformersas.marketplace.returns.domain.model;

/** Estados de una devolución (CU-19). Rechazada y Finalizada son terminales. */
public enum ReturnStatus {
    /** Solicitada por el comprador; el vendedor aún no la ha abierto. */
    REQUESTED,
    IN_REVIEW,
    /** El vendedor pidió información y el comprador tiene 24 h para responder. */
    INFO_REQUIRED,
    REJECTED,
    /** El vendedor la aprobó; sigue el retorno logístico (CU-25). */
    APPROVED,
    /** La mercancía llegó al vendedor: 24 h para reportar un problema. */
    IN_INSPECTION,
    REFUND_PENDING,
    FINISHED;

    public boolean isTerminal() {
        return this == REJECTED || this == FINISHED;
    }
}
