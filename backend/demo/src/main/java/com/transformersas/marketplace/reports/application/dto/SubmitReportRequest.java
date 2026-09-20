package com.transformersas.marketplace.reports.application.dto;

/**
 * Datos del reporte. Todo llega como texto y el servicio lo valida campo por campo, para indicar exactamente cuál
 * falta o es inválido (RF-146).
 */
public record SubmitReportRequest(String contentType, String contentId, String reason, String description) {
}
