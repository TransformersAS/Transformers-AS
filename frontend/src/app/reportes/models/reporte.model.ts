/** Contrato de los reportes de contenido (CU-20). Los estados ya vienen traducidos para quien reporta. */

export type EstadoReporte = 'PENDIENTE' | 'EN_REVISION' | 'ESPERANDO_INFORMACION' | 'RESUELTO';
export type ResultadoReporte = 'MANTENIDO' | 'OCULTO' | 'RETIRADO';
export type EstadoSolicitud = 'ABIERTA' | 'RESPONDIDA' | 'VENCIDA';

/** Motivo ofrecido por el sistema. `purchaseProblem` se atiende por reclamaciones y devoluciones, no aquí. */
export interface MotivoReporte {
    code: string;
    label: string;
    purchaseProblem: boolean;
}

export interface SolicitudReporte {
    contentType: string;
    contentId: string;
    reason: string;
    description: string;
}

/** Respuesta al radicar. Con `duplicate` verdadero ya existía un reporte activo con ese motivo y se devuelve ese. */
export interface ReporteRadicado {
    id: number;
    caseId: number;
    status: EstadoReporte;
    contentType: string;
    contentId: string;
    reason: string;
    reasonLabel: string;
    createdAt: string;
    evidenceCount: number;
    duplicate: boolean;
}

export interface ReporteResumen {
    id: number;
    caseId: number;
    contentType: string;
    contentId: string;
    reason: string;
    reasonLabel: string;
    status: EstadoReporte;
    createdAt: string;
    evidenceCount: number;
    awaitingYourResponse: boolean;
    medidaProvisional: 'OCULTO' | null;
}

export interface EvidenciaReporte {
    ordinal: number;
    fileName: string;
    contentType: string;
    sizeBytes: number;
}

export interface SolicitudInformacion {
    id: number;
    message: string;
    requestedAt: string;
    dueAt: string;
    status: EstadoSolicitud;
    respondedAt: string | null;
    responseText: string | null;
}

export interface ReporteDetalle {
    id: number;
    caseId: number;
    contentType: string;
    contentId: string;
    reason: string;
    reasonLabel: string;
    description: string;
    status: EstadoReporte;
    result: ResultadoReporte | null;
    medidaProvisional: 'OCULTO' | null;
    createdAt: string;
    resolvedAt: string | null;
    evidences: EvidenciaReporte[];
    informationRequests: SolicitudInformacion[];
}

/** Cuerpo de error de la API; `details.field` indica el campo del formulario al que corresponde. */
export interface ErrorReporte {
    status?: number;
    message?: string;
    code?: string;
    details?: { field?: string; reportId?: number } | null;
}

export const MAX_IMAGENES = 3;
export const MAX_BYTES_IMAGEN = 5 * 1024 * 1024;
/** Plazo para responder una solicitud de información (RF-148). */
export const HORAS_PLAZO_RESPUESTA = 72;

export const ETIQUETA_ESTADO: Record<EstadoReporte, string> = {
    PENDIENTE: 'Pendiente',
    EN_REVISION: 'En revisión',
    ESPERANDO_INFORMACION: 'Esperando tu información',
    RESUELTO: 'Resuelto'
};

export const ETIQUETA_RESULTADO: Record<ResultadoReporte, string> = {
    MANTENIDO: 'El contenido se mantuvo publicado.',
    OCULTO: 'El contenido quedó oculto.',
    RETIRADO: 'El contenido fue retirado.'
};

export const ETIQUETA_TIPO: Record<string, string> = {
    PUBLICACION: 'Publicación',
    TIENDA: 'Tienda',
    RESENA: 'Reseña',
    RESPUESTA_RESENA: 'Respuesta a reseña',
    MENSAJE: 'Mensaje'
};
