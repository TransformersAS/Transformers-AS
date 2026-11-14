package com.transformersas.marketplace.payments.infrastructure.mercadopago;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

class MercadoPagoRecoveryActivationTests {
    private final MercadoPagoRecoveryService recovery = mock(MercadoPagoRecoveryService.class);
    private final MercadoPagoFinancialService financial = mock(MercadoPagoFinancialService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withInitializer(c -> c.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
        .withUserConfiguration(MercadoPagoRecoveryScheduler.class)
        .withBean(MercadoPagoRecoveryService.class, () -> recovery)
        .withBean(MercadoPagoFinancialService.class, () -> financial);
    @Test void missingPropertyDoesNotCreateOrStartScheduler() {
        context.run(c -> { assertThat(c).doesNotHaveBean(MercadoPagoRecoveryScheduler.class);verifyNoInteractions(recovery,financial); });
    }
    @Test void explicitFalseDoesNotCreateOrStartScheduler() {
        context.withPropertyValues("payments.recovery.enabled=false").run(c -> {
            assertThat(c).doesNotHaveBean(MercadoPagoRecoveryScheduler.class);verifyNoInteractions(recovery,financial);
        });
    }
    @Test void explicitTrueStartsBothExistingWorkersAndContextCloseStopsScheduler() {
        var instance = new java.util.concurrent.atomic.AtomicReference<MercadoPagoRecoveryScheduler>();
        context.withPropertyValues("payments.recovery.enabled=true", "payments.recovery.initial-delay=0s", "payments.recovery.interval=25ms")
            .run(c -> {
                assertThat(c).hasSingleBean(MercadoPagoRecoveryScheduler.class);
                instance.set(c.getBean(MercadoPagoRecoveryScheduler.class));assertThat(instance.get().isRunning()).isTrue();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    verify(recovery,atLeastOnce()).recoverBatch();verify(financial,atLeastOnce()).recoverBatch();
                });
            });
        assertThat(instance.get().isRunning()).isFalse();
    }
}
