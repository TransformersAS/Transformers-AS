package com.transformersas.marketplace.reports.domain.port;

import com.transformersas.marketplace.reports.domain.model.ReportContentType;

import java.util.Optional;

/**
 * Contrato que implementa cada módulo dueño de un tipo de contenido (publicaciones, reseñas,
 * respuestas, tiendas, mensajes) para que soporte pueda ver lo reportado sin que {@code reports}
 * dependa de sus entidades.
 */
public interface ContentSnapshotProvider {

    ReportContentType type();

    Optional<ContentSnapshot> load(String contentId);
}
