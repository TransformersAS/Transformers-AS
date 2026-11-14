package com.transformersas.marketplace.suspensions.infrastructure.config;

import com.transformersas.marketplace.suspensions.application.usecase.ExpireDueSuspensionsUseCase;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Termina las suspensiones vencidas cada {@code stores.suspensions.sweep.interval} (1 minuto por defecto), así una
 * tienda vuelve a operar como mucho un minuto después de su fecha de fin (A5). Se apaga con
 * {@code stores.suspensions.sweep.enabled=false}, como hacen las pruebas para invocarlo a mano.
 */
@Component
@ConditionalOnProperty(name = "stores.suspensions.sweep.enabled", havingValue = "true", matchIfMissing = true)
class SuspensionSweepScheduler {

    private final ExpireDueSuspensionsUseCase expire;

    SuspensionSweepScheduler(ExpireDueSuspensionsUseCase expire) {
        this.expire = expire;
    }

    @Scheduled(fixedDelayString = "${stores.suspensions.sweep.interval:PT1M}")
    void run() {
        expire.runOnce();
    }
}
