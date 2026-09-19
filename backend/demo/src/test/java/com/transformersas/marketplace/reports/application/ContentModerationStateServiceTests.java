package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.domain.model.ContentModerationState;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ContentModerationStateEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ContentModerationStateId;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ContentModerationStateRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentModerationStateServiceTests {

    private final ContentModerationStateRepository repository = mock(ContentModerationStateRepository.class);
    private final ContentModerationStateService service =
            new ContentModerationStateService(repository, Clock.systemDefaultZone());

    private void messageIs(ContentModerationState state) {
        ContentModerationStateEntity entity = new ContentModerationStateEntity();
        entity.setContentType(ReportContentType.MENSAJE);
        entity.setContentId("msg_1");
        entity.setState(state);
        when(repository.findById(new ContentModerationStateId(ReportContentType.MENSAJE, "msg_1")))
                .thenReturn(Optional.of(entity));
    }

    @Test
    void removedMessageShowsTheStandardTextInsteadOfTheOriginal() {
        messageIs(ContentModerationState.RETIRADO);

        assertThat(service.renderMessageText("msg_1", "texto original ofensivo"))
                .isEqualTo("Mensaje retirado por moderación");
    }

    @Test
    void temporarilyHiddenMessageDoesNotRevealTheOriginalEither() {
        messageIs(ContentModerationState.OCULTO_TEMPORAL);

        assertThat(service.renderMessageText("msg_1", "texto original"))
                .isEqualTo(ContentVisibility.MESSAGE_HIDDEN_TEXT).doesNotContain("texto original");
    }

    @Test
    void visibleAndUnmoderatedMessagesKeepTheirText() {
        messageIs(ContentModerationState.VISIBLE);
        when(repository.findById(any(ContentModerationStateId.class))).thenReturn(Optional.empty());

        assertThat(service.renderMessageText("otro", "hola")).isEqualTo("hola");
    }
}
