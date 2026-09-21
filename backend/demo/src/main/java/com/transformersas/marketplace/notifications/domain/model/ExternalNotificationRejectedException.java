package com.transformersas.marketplace.notifications.domain.model;

/** El servicio externo rechazó el aviso de forma definitiva (4xx). */
public class ExternalNotificationRejectedException extends RuntimeException {

    public ExternalNotificationRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
