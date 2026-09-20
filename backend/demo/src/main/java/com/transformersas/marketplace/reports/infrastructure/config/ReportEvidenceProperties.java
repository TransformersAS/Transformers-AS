package com.transformersas.marketplace.reports.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Límites de las imágenes de evidencia de un reporte (RF-146): {@code reports.evidence.max-count} y
 * {@code reports.evidence.max-size}. El tamaño total de la petición lo limita, además, la configuración multipart.
 */
@ConfigurationProperties("reports.evidence")
public record ReportEvidenceProperties(@DefaultValue("3") int maxCount, @DefaultValue("5MB") DataSize maxSize) {
}
