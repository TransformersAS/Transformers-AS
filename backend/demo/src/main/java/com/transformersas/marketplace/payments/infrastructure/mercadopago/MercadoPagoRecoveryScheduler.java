package com.transformersas.marketplace.payments.infrastructure.mercadopago;

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

/** Hilo propio, igual que el poller logístico: la E/S del proveedor no bloquea otros temporizadores. */
@Component
@ConditionalOnProperty(name = "payments.recovery.enabled", havingValue = "true", matchIfMissing = false)
public class MercadoPagoRecoveryScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(MercadoPagoRecoveryScheduler.class);
    private final MercadoPagoRecoveryService recovery;
    private final MercadoPagoFinancialService financial;
    private final Duration interval;
    private final Duration initialDelay;
    private ScheduledExecutorService executor;

    public MercadoPagoRecoveryScheduler(MercadoPagoRecoveryService recovery, MercadoPagoFinancialService financial,
            @Value("${payments.recovery.interval:30s}") Duration interval,
            @Value("${payments.recovery.initial-delay:30s}") Duration initialDelay) {
        if (interval.toMillis() < 1 || initialDelay.isNegative()) throw new IllegalArgumentException("Intervalo de recuperación inválido");
        this.recovery = recovery; this.financial = financial; this.interval = interval; this.initialDelay = initialDelay;
    }

    public void recover() {
        CorrelationContext.runWith(null, () -> {
            try { recovery.recoverBatch(); financial.recoverBatch(); }
            catch (RuntimeException failure) {
                log.error("No se pudo ejecutar el barrido de pagos ({})", failure.getClass().getSimpleName());
            }
        });
    }

    @Override public synchronized void start() {
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "mercadopago-recovery");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(this::recover, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
    @Override public synchronized void stop() {
        if (executor != null) { executor.shutdownNow(); executor = null; }
    }
    @Override public synchronized boolean isRunning() { return executor != null; }
}
