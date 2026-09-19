package com.transformersas.marketplace.reports.domain.model;

/** Estado de un caso de moderación (agrupa todos los reportes sobre un mismo contenido). */
public enum ReportStatus {
    PENDIENTE,
    EN_REVISION,
    INFO_SOLICITADA,
    RESUELTO
}
