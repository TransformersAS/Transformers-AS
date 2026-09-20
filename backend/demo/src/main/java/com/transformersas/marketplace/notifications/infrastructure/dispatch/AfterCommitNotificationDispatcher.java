package com.transformersas.marketplace.notifications.infrastructure.dispatch;

import com.transformersas.marketplace.notifications.application.dto.NotificationPublished;
import com.transformersas.marketplace.notifications.application.usecase.DispatchExternalNotificationUseCase;
import com.transformersas.marketplace.shared.web.CorrelationContext;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Despacha el aviso externo DESPUÉS del commit de la transacción que publicó la notificación (nada sale si hubo
 * rollback) y fuera del hilo de la petición. El ejecutor está acotado (2-4 hilos, cola de 100): si se satura, el
 * aviso no se pierde, la notificación queda external_status=PENDING para un barrido posterior y la petición no falla.
 */
@Component
public class AfterCommitNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitNotificationDispatcher.class);

    private final DispatchExternalNotificationUseCase dispatch;
    private final ThreadPoolExecutor executor;

    @Autowired
    public AfterCommitNotificationDispatcher(DispatchExternalNotificationUseCase dispatch) {
        this(dispatch, 2, 4, 100);
    }

    public AfterCommitNotificationDispatcher(DispatchExternalNotificationUseCase dispatch, int core, int max, int queue) {
        this.dispatch = dispatch;
        var counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(core, max, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue),
                runnable -> {
                    Thread thread = new Thread(runnable, "notification-dispatch-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPublished(NotificationPublished event) {
        try {
            executor.execute(() -> CorrelationContext.runWith(event.correlationId(), () -> {
                try {
                    dispatch.execute(event.notificationId());
                } catch (RuntimeException unexpected) {
                    // El caso de uso no lanza; esto protege al hilo del pool ante un fallo de infraestructura.
                    log.error("Fallo despachando notificationId={}", event.notificationId(), unexpected);
                }
            }));
        } catch (RejectedExecutionException saturated) {
            log.warn("Despacho externo saturado: la notificación {} queda PENDING", event.notificationId());
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
