package com.transformersas.marketplace.reports.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Locale;

/**
 * Última defensa de RF-161: los textos que escribe el agente (mensajes y justificaciones) pueden
 * llegar al responsable del contenido, así que se rechazan si citan a quien reportó.
 */
final class ReporterIdentityGuard {

    private static final int MIN_ID_LENGTH = 3;

    private ReporterIdentityGuard() {
    }

    static void ensureAbsent(String text, Collection<String> reporterIds) {
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String reporterId : reporterIds) {
            if (reporterId != null && reporterId.length() >= MIN_ID_LENGTH
                    && normalized.contains(reporterId.toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El texto no debe incluir la identidad de quienes reportaron.");
            }
        }
    }
}
