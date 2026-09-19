package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.domain.model.NotificationChannel;
import com.transformersas.marketplace.reports.domain.model.NotificationStatus;
import com.transformersas.marketplace.reports.infrastructure.persistence.entity.ModerationNotificationEntity;
import com.transformersas.marketplace.reports.infrastructure.persistence.repository.ModerationNotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Crea las notificaciones de moderación (RF-161): una interna, entregada al instante, y una fila de
 * outbox para el servicio externo. Si ese servicio cae la decisión no se bloquea: el despachador
 * reintenta después.
 *
 * <p>Los payloads son las únicas formas en que se comunica algo a un usuario. Están escritos como
 * listas blancas de campos y ninguno contiene datos de quien reportó; el texto libre del reporte
 * (descripción) tampoco se incluye porque podría identificarlo.
 */
@Service
public class ModerationNotificationService {

    public static final String INFORMATION_REQUESTED = "INFORMATION_REQUESTED";
    public static final String DECISION_TO_OWNER = "MODERATION_DECISION_OWNER";
    public static final String OUTCOME_TO_REPORTER = "REPORT_OUTCOME_REPORTER";
    public static final String INFORMATION_RESPONDED = "INFORMATION_RESPONDED";
    public static final String INFORMATION_EXPIRED = "INFORMATION_EXPIRED";

    /** Solicitud de información dirigida a un reportador o al propietario. */
    public record InformationRequestPayload(Long requestId, String contentType, String contentId, String message,
                                            LocalDateTime dueAt) {
    }

    /** Decisión comunicada al responsable del contenido: sin autores, sin número ni texto de reportes. */
    public record OwnerDecisionPayload(String contentType, String contentId, String decision, String justification,
                                       List<String> reasonCategories) {
    }

    /** Resultado comunicado a quien reportó. */
    public record ReporterOutcomePayload(String contentType, String contentId, String outcome) {
    }

    /** Aviso interno al agente asignado. */
    public record AgentNoticePayload(Long caseId, Long requestId) {
    }

    private final ModerationNotificationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ModerationNotificationService(ModerationNotificationRepository repository, ObjectMapper objectMapper,
                                         Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Notificación interna más envío por el servicio externo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyUser(Long caseId, String recipientId, String template, Object payload) {
        String json = objectMapper.writeValueAsString(payload);
        LocalDateTime now = LocalDateTime.now(clock);
        repository.save(build(caseId, recipientId, NotificationChannel.INTERNAL, template, json,
                NotificationStatus.ENVIADA, now));
        repository.save(build(caseId, recipientId, NotificationChannel.EXTERNAL, template, json,
                NotificationStatus.PENDIENTE, now));
    }

    /** Solo notificación interna (avisos a agentes). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyInternal(Long caseId, String recipientId, String template, Object payload) {
        repository.save(build(caseId, recipientId, NotificationChannel.INTERNAL, template,
                objectMapper.writeValueAsString(payload), NotificationStatus.ENVIADA, LocalDateTime.now(clock)));
    }

    private ModerationNotificationEntity build(Long caseId, String recipientId, NotificationChannel channel,
                                               String template, String payload, NotificationStatus status,
                                               LocalDateTime now) {
        ModerationNotificationEntity entity = new ModerationNotificationEntity();
        entity.setCaseId(caseId);
        entity.setRecipientId(recipientId);
        entity.setChannel(channel);
        entity.setTemplate(template);
        entity.setPayload(payload);
        entity.setStatus(status);
        entity.setCreatedAt(now);
        if (channel == NotificationChannel.EXTERNAL) {
            entity.setNextAttemptAt(now);
        } else {
            entity.setSentAt(now);
        }
        return entity;
    }
}
