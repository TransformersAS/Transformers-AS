package com.transformersas.marketplace.notifications.infrastructure.gateway;

import com.transformersas.marketplace.notifications.domain.model.ExternalNotificationRejectedException;
import com.transformersas.marketplace.notifications.domain.model.ExternalNotificationUnavailableException;
import com.transformersas.marketplace.notifications.domain.repository.ExternalNotificationGateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servicio externo de notificaciones simulado (por defecto, RNF-037). Registra los avisos aceptados y permite
 * simular fallos con notifications.simulated.mode o cambiando el modo en caliente desde pruebas.
 */
@Component
@ConditionalOnProperty(name = "notifications.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedExternalNotificationGateway implements ExternalNotificationGateway {

    public enum Mode { OK, UNAVAILABLE, REJECT }

    private volatile Mode mode;
    private final List<Request> accepted = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    public SimulatedExternalNotificationGateway(@Value("${notifications.simulated.mode:OK}") Mode mode) {
        this.mode = mode;
    }

    @Override
    public void send(Request request) {
        requests.incrementAndGet();
        switch (mode) {
            case UNAVAILABLE -> throw new ExternalNotificationUnavailableException(
                    "Servicio externo de notificaciones simulado no disponible", null);
            case REJECT -> throw new ExternalNotificationRejectedException(
                    "Aviso rechazado por el servicio externo simulado", null);
            case OK -> accepted.add(request);
        }
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** Avisos aceptados por el proveedor simulado. */
    public List<Request> accepted() {
        return List.copyOf(accepted);
    }

    /** Solicitudes recibidas, aceptadas o no. */
    public int requestCount() {
        return requests.get();
    }

    public void reset() {
        accepted.clear();
        requests.set(0);
        mode = Mode.OK;
    }
}
