package com.transformersas.marketplace.logistics.domain.model;

/**
 * Fallo temporal del proveedor: 5xx, timeout, error de red, respuesta inválida o circuito abierto.
 * Es seguro reintentar porque la creación es idempotente por clave.
 */
public class LogisticsUnavailableException extends RuntimeException {

    public LogisticsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
