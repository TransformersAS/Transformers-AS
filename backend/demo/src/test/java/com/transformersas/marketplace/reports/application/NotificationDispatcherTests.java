package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.domain.model.NotificationChannel;
import com.transformersas.marketplace.reports.domain.model.NotificationStatus;
import com.transformersas.marketplace.reports.domain.port.ExternalNotificationClient;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationNotificationEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationNotificationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationDispatcherTests {

    private static final Instant NOW = Instant.parse("2026-09-19T15:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final ModerationNotificationRepository repository = mock(ModerationNotificationRepository.class);
    private final ExternalNotificationClient client = mock(ExternalNotificationClient.class);
    private final NotificationDispatcher dispatcher =
            new NotificationDispatcher(repository, client, Clock.fixed(NOW, ZoneOffset.UTC));

    private ModerationNotificationEntity pending(int attempts) {
        ModerationNotificationEntity notification = new ModerationNotificationEntity();
        notification.setId(1L);
        notification.setRecipientId("seller_9");
        notification.setChannel(NotificationChannel.EXTERNAL);
        notification.setTemplate("MODERATION_DECISION_OWNER");
        notification.setPayload("{}");
        notification.setStatus(NotificationStatus.PENDIENTE);
        notification.setAttempts(attempts);
        when(repository.findTop50ByChannelAndStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(any(), any(), any()))
                .thenReturn(List.of(notification));
        return notification;
    }

    @Test
    void successfulSendMarksTheNotificationAsSent() {
        ModerationNotificationEntity notification = pending(0);

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.ENVIADA);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void failuresBackOffExponentially() {
        doThrow(new IllegalStateException("servicio caído")).when(client).send(any(), any(), any());

        ModerationNotificationEntity first = pending(0);
        dispatcher.dispatchDue();
        assertThat(first.getStatus()).isEqualTo(NotificationStatus.PENDIENTE);
        assertThat(first.getNextAttemptAt()).isEqualTo(NOW_LOCAL.plusMinutes(1));
        assertThat(first.getLastError()).contains("servicio caído");

        ModerationNotificationEntity third = pending(2);
        dispatcher.dispatchDue();
        assertThat(third.getNextAttemptAt()).isEqualTo(NOW_LOCAL.plusMinutes(4));
    }

    @Test
    void givesUpAfterTheMaximumNumberOfAttempts() {
        doThrow(new IllegalStateException("servicio caído")).when(client).send(any(), any(), any());
        ModerationNotificationEntity notification = pending(NotificationDispatcher.MAX_ATTEMPTS - 1);

        assertThat(dispatcher.dispatchDue()).isZero();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FALLIDA);
        assertThat(notification.getNextAttemptAt()).isNull();
    }
}
