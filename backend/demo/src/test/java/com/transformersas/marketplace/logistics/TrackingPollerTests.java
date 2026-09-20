package com.transformersas.marketplace.logistics;

import com.transformersas.marketplace.logistics.application.usecase.PollActiveTrackingUseCase;
import com.transformersas.marketplace.logistics.infrastructure.scheduling.TrackingPoller;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Barrido periódico del seguimiento (A7): se programa, nunca muere por un error y se detiene limpiamente. */
class TrackingPollerTests {

    private final PollActiveTrackingUseCase poll = mock(PollActiveTrackingUseCase.class);

    @Test
    void aSweepRunsTheUseCaseOnceAndNeverThrows() {
        var poller = new TrackingPoller(poll, Duration.ofSeconds(30));
        when(poll.execute()).thenReturn(3).thenReturn(0).thenThrow(new IllegalStateException("BD caída"));

        assertThatCode(poller::pollOnce).doesNotThrowAnyException();
        assertThatCode(poller::pollOnce).doesNotThrowAnyException();
        assertThatCode(poller::pollOnce).doesNotThrowAnyException();

        verify(poll, atLeast(3)).execute();
    }

    @Test
    void startSchedulesRepeatedSweepsAndStopCancelsThem() {
        var poller = new TrackingPoller(poll, Duration.ofMillis(30));
        when(poll.execute()).thenReturn(0);
        assertThat(poller.isRunning()).isFalse();

        poller.start();
        poller.start(); // idempotente: no crea un segundo ejecutor
        assertThat(poller.isRunning()).isTrue();
        verify(poll, timeout(3_000).atLeast(2)).execute();

        poller.stop();
        assertThat(poller.isRunning()).isFalse();
        assertThatCode(poller::stop).doesNotThrowAnyException();
    }

    @Test
    void aFailingSweepDoesNotStopTheNextOnes() {
        var poller = new TrackingPoller(poll, Duration.ofMillis(30));
        when(poll.execute()).thenThrow(new IllegalStateException("fallo")).thenReturn(1);

        poller.start();
        try {
            verify(poll, timeout(3_000).atLeast(3)).execute();
        } finally {
            poller.stop();
        }
    }
}
