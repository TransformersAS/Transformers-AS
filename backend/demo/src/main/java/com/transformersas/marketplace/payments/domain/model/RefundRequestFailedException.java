package com.transformersas.marketplace.payments.domain.model;

/** La pasarela no pudo procesar el reembolso (rechazo, indisponibilidad o timeout). El reembolso queda FAILED y es reintentable. */
public class RefundRequestFailedException extends RuntimeException {

    public RefundRequestFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
