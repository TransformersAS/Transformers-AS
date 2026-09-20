package com.transformersas.marketplace.reports.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class ModerationSchedulerTests {
    private final InformationRequestService requests = mock(InformationRequestService.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final ModerationScheduler scheduler = new ModerationScheduler(requests, dispatcher);

    @ParameterizedTest @ValueSource(ints = {0, 2})
    void expiresRequestsWhetherOrNotAnyAreOverdue(int expired) {
        when(requests.expireOverdue()).thenReturn(expired);
        scheduler.expireInformationRequests();
        verify(requests).expireOverdue();
        verifyNoInteractions(dispatcher);
    }
    @Test
    void expirationFailureDoesNotPreventNextRunOrDispatch() {
        when(requests.expireOverdue()).thenThrow(new IllegalStateException("Database unavailable")).thenReturn(1);
        assertThatCode(scheduler::expireInformationRequests).doesNotThrowAnyException();
        scheduler.expireInformationRequests();
        scheduler.dispatchNotifications();
        verify(requests, times(2)).expireOverdue();
        verify(dispatcher).dispatchDue();
    }
    @Test
    void dispatchFailureDoesNotPreventNextRunOrExpiration() {
        when(dispatcher.dispatchDue()).thenThrow(new IllegalStateException("Delivery unavailable")).thenReturn(1);
        assertThatCode(scheduler::dispatchNotifications).doesNotThrowAnyException();
        scheduler.dispatchNotifications();
        scheduler.expireInformationRequests();
        verify(dispatcher, times(2)).dispatchDue();
        verify(requests).expireOverdue();
    }
}
