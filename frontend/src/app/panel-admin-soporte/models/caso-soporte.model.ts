export type EstadoCaso = 'PENDIENTE' | 'EN_REVISION' | 'INFO_SOLICITADA' | 'RESUELTO';
export type DecisionModeracion = 'MANTENER' | 'OCULTAR_TEMPORALMENTE' | 'RETIRAR';
export type DestinoInformacion = 'REPORTADOR' | 'PROPIETARIO';

export interface PaginaResultados<T> {
    items: T[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

export interface CasoModeracion {
    id: number;
    contentType: string;
    contentId: string;
    status: EstadoCaso;
    reportCount: number;
    assignedAgentId?: string | null;
    contentState: 'VISIBLE' | 'OCULTO_TEMPORAL' | 'RETIRADO';
    openedAt: string;
    resolvedAt?: string | null;
    version: number;
}

export interface EvidenciaReporte {
    id: number;
    fileUrl: string;
    fileType: string;
    fileSize?: number | null;
}

export interface ReporteItem {
    id: number;
    reporterId: string;
    reason: string;
    description?: string | null;
    createdAt: string;
    evidences: EvidenciaReporte[];
}

export interface SolicitudInformacion {
    id: number;
    target: DestinoInformacion;
    targetUserId: string;
    message: string;
    requestedBy: string;
    requestedAt: string;
    dueAt: string;
    status: 'ABIERTA' | 'RESPONDIDA' | 'VENCIDA';
    respondedAt?: string | null;
    responseText?: string | null;
}

export interface DecisionItem {
    id: number;
    agentId: string;
    decision: DecisionModeracion;
    justification: string;
    measureResult: 'APLICADA' | 'YA_APLICADA';
    createdAt: string;
}

export interface HistorialCaso {
    caseId: number;
    openedAt: string;
    resolvedAt?: string | null;
    reportCount: number;
    decisions: DecisionItem[];
}

export interface ContenidoReportado {
    title?: string | null;
    text?: string | null;
    ownerId?: string | null;
    attributes: Record<string, string>;
}

export interface CasoDetalle extends CasoModeracion {
    content?: ContenidoReportado | null;
    reports: ReporteItem[];
    informationRequests: SolicitudInformacion[];
    decisions: DecisionItem[];
    history: HistorialCaso[];
}

export interface ResultadoDecision {
    decisionId: number;
    decision: DecisionModeracion;
    measureResult: 'APLICADA' | 'YA_APLICADA';
    caseStatus: EstadoCaso;
    contentState: string;
}
