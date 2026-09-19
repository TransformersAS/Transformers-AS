package com.transformersas.marketplace.reports.application;

import com.transformersas.marketplace.reports.domain.model.ContentModerationState;
import com.transformersas.marketplace.reports.domain.model.ReportContentType;

import java.util.Set;

/**
 * Contrato público con el que los módulos dueños del contenido (publicaciones, reseñas, respuestas,
 * tiendas, mensajes) respetan la moderación al mostrarlo. El contenido original nunca se modifica:
 * solo cambia lo que se muestra.
 */
public interface ContentVisibility {

    String MESSAGE_REMOVED_TEXT = "Mensaje retirado por moderación";
    String MESSAGE_HIDDEN_TEXT = "Mensaje oculto temporalmente por moderación";

    ContentModerationState stateOf(ReportContentType type, String contentId);

    /** Ids de ese tipo que no deben mostrarse (ocultos temporalmente o retirados). */
    Set<String> nonVisibleIds(ReportContentType type);

    /**
     * Texto que ven los participantes de una conversación: el original si el mensaje está visible y
     * el texto estándar de moderación si fue retirado u ocultado. El original se conserva intacto.
     */
    String renderMessageText(String messageId, String originalText);
}
