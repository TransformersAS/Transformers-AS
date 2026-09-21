package com.transformersas.marketplace.returns.infrastructure.config;

import com.transformersas.marketplace.returns.application.usecase.ReturnSweepUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Corre el barrido de devoluciones cada {@code returns.sweep.interval} (1 minuto por defecto). Se apaga con
 * {@code returns.sweep.enabled=false}, como hacen las pruebas para invocarlo a mano. Cada réplica lo corre; el
 * caso de uso evita que se pisen.
 */
@Component
@ConditionalOnProperty(name = "returns.sweep.enabled", havingValue = "true", matchIfMissing = true)
class ReturnSweepScheduler {
    private final ReturnSweepUseCase sweep;

    ReturnSweepScheduler(ReturnSweepUseCase sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${returns.sweep.interval:PT1M}")
    void run() {
        sweep.runOnce();
    }
}
