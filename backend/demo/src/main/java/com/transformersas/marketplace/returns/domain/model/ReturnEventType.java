package com.transformersas.marketplace.returns.domain.model;

/** Hechos de la línea de tiempo de una devolución (RF-051). Algunos no cambian el estado. */
public enum ReturnEventType {
    REQUESTED,
    APPROVED_FROM_CLAIM,
    REVIEW_STARTED,
    INFORMATION_REQUESTED,
    INFORMATION_ANSWERED,
    REJECTED,
    APPROVED,
    METHOD_CHOSEN,
    INSPECTION_STARTED,
    PROBLEM_REPORTED,
    REFUND_REQUESTED,
    REFUND_RETRY_SCHEDULED,
    REFUND_GAVE_UP,
    FINISHED
}
