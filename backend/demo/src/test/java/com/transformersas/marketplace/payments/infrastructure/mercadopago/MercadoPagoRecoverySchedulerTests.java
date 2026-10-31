package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MercadoPagoRecoverySchedulerTests {
    @Test void startsOnlyOneExecutorAndStopsWithoutInvokingRealProviders() {
        var recovery = mock(MercadoPagoRecoveryService.class);
        var scheduler = new MercadoPagoRecoveryScheduler(recovery, mock(MercadoPagoFinancialService.class), Duration.ofHours(1), Duration.ofHours(1));
        try {
            scheduler.start(); scheduler.start();
            assertThat(scheduler.isRunning()).isTrue();
        } finally { scheduler.stop(); }
        assertThat(scheduler.isRunning()).isFalse();
        verifyNoInteractions(recovery);
    }
    @Test void transientFailureDoesNotCancelFollowingScheduledRuns() throws Exception {
        var recovery = mock(MercadoPagoRecoveryService.class);
        var recovered = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(recovery.recoverBatch()).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("simulated");
            recovered.countDown(); return 1;
        });
        var scheduler = new MercadoPagoRecoveryScheduler(recovery, mock(MercadoPagoFinancialService.class), Duration.ofMillis(25), Duration.ZERO);
        try {
            scheduler.start();
            assertThat(recovered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally { scheduler.stop(); }
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
    }
}
