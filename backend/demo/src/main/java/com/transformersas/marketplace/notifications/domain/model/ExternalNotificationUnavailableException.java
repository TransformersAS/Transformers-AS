package com.transformersas.marketplace.notifications.domain.model;

/** Fallo temporal del servicio externo: 5xx, timeout, red, respuesta inválida o circuito abierto. */
public class ExternalNotificationUnavailableException extends RuntimeException {

    public ExternalNotificationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
