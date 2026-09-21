package com.transformersas.marketplace.notifications;

import com.transformersas.marketplace.notifications.application.dto.NotificationPublished;
import com.transformersas.marketplace.notifications.application.usecase.DispatchExternalNotificationUseCase;
import com.transformersas.marketplace.notifications.infrastructure.dispatch.AfterCommitNotificationDispatcher;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** El ejecutor de avisos externos es acotado y nunca hace fallar a quien confirma la transacción. */
class AfterCommitNotificationDispatcherTests {

    @Test
    void dispatchRunsOnAnotherThreadWithTheOriginalCorrelationId() {
        var dispatch = mock(DispatchExternalNotificationUseCase.class);
        AtomicReference<String> correlation = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        doAnswer(call -> {
            correlation.set(MDC.get("correlationId"));
            thread.set(Thread.currentThread().getName());
            return null;
        }).when(dispatch).execute(anyLong());
        var dispatcher = new AfterCommitNotificationDispatcher(dispatch, 1, 1, 1);

        dispatcher.onPublished(new NotificationPublished(5L, "corr-async-9"));

        await().atMost(Duration.ofSeconds(5)).until(() -> correlation.get() != null);
        assertThat(correlation.get()).isEqualTo("corr-async-9");
        assertThat(thread.get()).startsWith("notification-dispatch-").isNotEqualTo(Thread.currentThread().getName());
        assertThat(MDC.get("correlationId")).isNull(); // el hilo llamador no se contamina
    }

    @Test
    void aSaturatedExecutorLeavesTheNotificationPendingWithoutThrowing() throws Exception {
        var dispatch = mock(DispatchExternalNotificationUseCase.class);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            release.await();
            return null;
        }).when(dispatch).execute(1L);
        var dispatcher = new AfterCommitNotificationDispatcher(dispatch, 1, 1, 1);

        dispatcher.onPublished(new NotificationPublished(1L, "c1")); // ocupa el único hilo
        started.await();
        dispatcher.onPublished(new NotificationPublished(2L, "c2")); // ocupa la única plaza de la cola

        assertThatCode(() -> dispatcher.onPublished(new NotificationPublished(3L, "c3"))).doesNotThrowAnyException();

        release.countDown();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(dispatch).execute(2L));
        verify(dispatch, never()).execute(3L); // rechazada: queda PENDING para un barrido posterior
    }

    @Test
    void anUnexpectedFailureInsideTheTaskDoesNotKillTheWorker() {
        var dispatch = mock(DispatchExternalNotificationUseCase.class);
        doThrow(new IllegalStateException("BD caída")).when(dispatch).execute(1L);
        var dispatcher = new AfterCommitNotificationDispatcher(dispatch, 1, 1, 5);

        dispatcher.onPublished(new NotificationPublished(1L, "c1"));
        dispatcher.onPublished(new NotificationPublished(2L, "c2"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(dispatch).execute(2L));
    }
}
