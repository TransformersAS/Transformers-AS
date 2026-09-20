package com.transformersas.marketplace.reports.domain.model;

/**
 * Resultado final de un reporte que ve quien lo radicó (RF-149): solo qué pasó con el contenido, sin la justificación
 * ni el agente. Un caso resuelto siempre termina en MANTENIDO o RETIRADO; ocultar temporalmente es una medida cautelar
 * que deja el caso abierto y no se presenta como resultado.
 */
public enum ReporterOutcome {
    MANTENIDO,
    OCULTO,
    RETIRADO;

    public static ReporterOutcome from(ModerationDecision decision) {
        return switch (decision) {
            case MANTENER -> MANTENIDO;
            case OCULTAR_TEMPORALMENTE -> OCULTO;
            case RETIRAR -> RETIRADO;
        };
    }
}
