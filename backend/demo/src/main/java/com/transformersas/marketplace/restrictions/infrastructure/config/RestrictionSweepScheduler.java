package com.transformersas.marketplace.restrictions.infrastructure.config;

import com.transformersas.marketplace.restrictions.application.usecase.ExpireDueRestrictionsUseCase;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Termina las restricciones temporales vencidas cada {@code restrictions.sweep.interval} (1 minuto por defecto),
 * así una cuenta queda libre como mucho un minuto después de su fecha de fin (A5). Se apaga con
 * {@code restrictions.sweep.enabled=false}, como hacen las pruebas para invocarlo a mano.
 */
@Component
@ConditionalOnProperty(name = "restrictions.sweep.enabled", havingValue = "true", matchIfMissing = true)
class RestrictionSweepScheduler {

    private final ExpireDueRestrictionsUseCase expire;

    RestrictionSweepScheduler(ExpireDueRestrictionsUseCase expire) {
        this.expire = expire;
    }

    @Scheduled(fixedDelayString = "${restrictions.sweep.interval:PT1M}")
    void run() {
        expire.runOnce();
    }
}
