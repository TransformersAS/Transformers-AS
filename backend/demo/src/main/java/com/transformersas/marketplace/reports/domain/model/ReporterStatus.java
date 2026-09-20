package com.transformersas.marketplace.reports.domain.model;

/**
 * Estado de un reporte tal como lo ve quien lo radicó (RF-149). Traduce el estado del caso de CU-21 sin
 * modificarlo: INFO_SOLICITADA pasa a ESPERANDO_INFORMACION. Las remisiones a administración de cuentas y el vencimiento
 * de una solicitud de información no cambian el estado del caso, así que no tienen traducción propia.
 */
public enum ReporterStatus {
    PENDIENTE,
    EN_REVISION,
    ESPERANDO_INFORMACION,
    RESUELTO;

    public static ReporterStatus from(ReportStatus caseStatus) {
        return switch (caseStatus) {
            case PENDIENTE -> PENDIENTE;
            case EN_REVISION -> EN_REVISION;
            case INFO_SOLICITADA -> ESPERANDO_INFORMACION;
            case RESUELTO -> RESUELTO;
        };
    }
}
