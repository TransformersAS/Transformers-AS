package com.transformersas.marketplace.logistics.infrastructure.scheduling;

import com.transformersas.marketplace.logistics.application.usecase.PollActiveTrackingUseCase;
import com.transformersas.marketplace.shared.web.CorrelationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Barrido periódico del seguimiento logístico (A7): mientras haya envíos o devoluciones activos vuelve a consultar al
 * servicio logístico. Un solo hilo con retardo fijo entre barridos, de modo que nunca se solapan en esta instancia.
 * Se activa con logistics.tracking.polling-enabled (por defecto true; las pruebas lo desactivan).
 */
@Component
@ConditionalOnProperty(name = "logistics.tracking.polling-enabled", havingValue = "true", matchIfMissing = true)
public class TrackingPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TrackingPoller.class);

    private final PollActiveTrackingUseCase poll;
    private final Duration interval;
    private ScheduledExecutorService executor;

    public TrackingPoller(PollActiveTrackingUseCase poll,
                          @Value("${logistics.tracking.polling-interval:30s}") Duration interval) {
        this.poll = poll;
        this.interval = interval;
    }

    /** Un barrido. Nunca lanza: una excepción cancelaría los barridos siguientes del ejecutor. */
    public void pollOnce() {
        CorrelationContext.runWith(null, () -> {
            try {
                int polled = poll.execute();
                if (polled > 0) {
                    log.info("Barrido de seguimiento logístico: {} consultados", polled);
                }
            } catch (RuntimeException failure) {
                log.error("Falló el barrido de seguimiento logístico", failure);
            }
        });
    }

    @Override
    public synchronized void start() {
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "logistics-tracking-poller");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(this::pollOnce, interval.toMillis(), interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
