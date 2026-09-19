package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.domain.model.ContentModerationState;
import com.transformersas.marketplace.reports.domain.model.MeasureResult;
import com.transformersas.marketplace.reports.domain.model.ModerationDecision;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ContentModerationStateEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ContentModerationStateId;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ContentModerationStateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

/** Guarda y aplica el estado de moderación de cada contenido (RF-160). */
@Service
public class ContentModerationStateService implements ContentVisibility {

    public record AppliedMeasure(MeasureResult result, ContentModerationState previous,
                                 ContentModerationState current) {
    }

    private final ContentModerationStateRepository repository;
    private final Clock clock;

    public ContentModerationStateService(ContentModerationStateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ContentModerationState stateOf(ReportContentType type, String contentId) {
        return repository.findById(new ContentModerationStateId(type, contentId))
                .map(ContentModerationStateEntity::getState)
                .orElse(ContentModerationState.VISIBLE);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> nonVisibleIds(ReportContentType type) {
        return repository.findByContentTypeAndStateNot(type, ContentModerationState.VISIBLE).stream()
                .map(ContentModerationStateEntity::getContentId)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public String renderMessageText(String messageId, String originalText) {
        return switch (stateOf(ReportContentType.MENSAJE, messageId)) {
            case RETIRADO -> MESSAGE_REMOVED_TEXT;
            case OCULTO_TEMPORAL -> MESSAGE_HIDDEN_TEXT;
            case VISIBLE -> originalText;
        };
    }

    /**
     * Aplica la decisión sobre el contenido dentro de la transacción del caso. Es idempotente: si el
     * contenido ya está en el estado pedido devuelve YA_APLICADA. Un contenido retirado no se
     * restaura ni se oculta por esta vía.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppliedMeasure apply(ReportContentType type, String contentId, ModerationDecision decision,
                                Long caseId) {
        ContentModerationState target = switch (decision) {
            case MANTENER -> ContentModerationState.VISIBLE;
            case OCULTAR_TEMPORALMENTE -> ContentModerationState.OCULTO_TEMPORAL;
            case RETIRAR -> ContentModerationState.RETIRADO;
        };
        ContentModerationState current = stateOf(type, contentId);

        if (current == ContentModerationState.RETIRADO && target != ContentModerationState.RETIRADO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El contenido ya fue retirado.");
        }
        if (current == target) {
            return new AppliedMeasure(MeasureResult.YA_APLICADA, current, current);
        }

        ContentModerationStateEntity entity = repository.findById(new ContentModerationStateId(type, contentId))
                .orElseGet(() -> {
                    ContentModerationStateEntity created = new ContentModerationStateEntity();
                    created.setContentType(type);
                    created.setContentId(contentId);
                    return created;
                });
        entity.setState(target);
        entity.setCaseId(caseId);
        entity.setUpdatedAt(LocalDateTime.now(clock));
        repository.save(entity);
        return new AppliedMeasure(MeasureResult.APLICADA, current, target);
    }
}
