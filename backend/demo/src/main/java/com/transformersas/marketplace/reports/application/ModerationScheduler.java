package com.transformersas.marketplace.reports.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tareas periódicas del caso de uso. Se pueden apagar con {@code moderation.scheduling.enabled=false}
 * (las pruebas invocan los servicios directamente). Con varias instancias conviene añadir un
 * candado distribuido; ambas tareas son idempotentes, así que un solapamiento no corrompe datos.
 */
@Component
@ConditionalOnProperty(name = "moderation.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class ModerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ModerationScheduler.class);

    private final InformationRequestService informationRequests;
    private final NotificationDispatcher dispatcher;

    ModerationScheduler(InformationRequestService informationRequests, NotificationDispatcher dispatcher) {
        this.informationRequests = informationRequests;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${moderation.deadline-check-interval:PT5M}")
    void expireInformationRequests() {
        try {
            int expired = informationRequests.expireOverdue();
            if (expired > 0) {
                log.info("{} solicitudes de información vencidas.", expired);
            }
        } catch (RuntimeException failure) {
            log.error("Falló el vencimiento de solicitudes de información.", failure);
        }
    }

    @Scheduled(fixedDelayString = "${moderation.notification-dispatch-interval:PT30S}")
    void dispatchNotifications() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException failure) {
            log.error("Falló el despacho de notificaciones.", failure);
        }
    }
}
