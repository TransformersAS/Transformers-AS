package com.transformersas.marketplace.logistics.domain.model;

/** El proveedor rechazó la solicitud de forma definitiva (4xx): reintentar sin cambiar los datos no ayuda. */
public class LogisticsRejectedException extends RuntimeException {

    public LogisticsRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
