/** Contrato de las devoluciones de compra (CU-19). Los estados llegan como códigos y aquí se traducen al español. */

export type EstadoDevolucion =
    | 'REQUESTED' | 'IN_REVIEW' | 'INFO_REQUIRED' | 'REJECTED' | 'APPROVED' | 'IN_INSPECTION' | 'REFUND_PENDING'
    | 'FINISHED';

export const ETIQUETA_ESTADO: Record<EstadoDevolucion, string> = {
    REQUESTED: 'Solicitada',
    IN_REVIEW: 'En revisión',
    INFO_REQUIRED: 'Información adicional requerida',
    REJECTED: 'Rechazada',
    APPROVED: 'Aprobada',
    IN_INSPECTION: 'En inspección',
    REFUND_PENDING: 'Reembolso pendiente',
    FINISHED: 'Finalizada'
};

export const COLOR_ESTADO: Record<EstadoDevolucion, string> = {
    REQUESTED: 'medium',
    IN_REVIEW: 'primary',
    INFO_REQUIRED: 'warning',
    REJECTED: 'danger',
    APPROVED: 'success',
    IN_INSPECTION: 'tertiary',
    REFUND_PENDING: 'warning',
    FINISHED: 'success'
};

/** Motivos que ofrece el sistema al solicitar (los mismos códigos que el backend). */
export const MOTIVOS_DEVOLUCION: { codigo: string; etiqueta: string }[] = [
    { codigo: 'DEFECTIVE', etiqueta: 'El producto llegó defectuoso' },
    { codigo: 'NOT_AS_DESCRIBED', etiqueta: 'No es como se describía' },
    { codigo: 'DAMAGED_IN_TRANSIT', etiqueta: 'Se dañó en el envío' },
    { codigo: 'WRONG_ITEM', etiqueta: 'Recibí otro producto' },
    { codigo: 'CHANGED_MIND', etiqueta: 'Ya no lo quiero' },
    { codigo: 'OTHER', etiqueta: 'Otro motivo' }
];

export const ETIQUETA_EVENTO: Record<string, string> = {
    REQUESTED: 'Devolución solicitada',
    APPROVED_FROM_CLAIM: 'Aprobada a partir de una reclamación',
    REOPENED_FROM_CLAIM: 'Reabierta por una reclamación',
    ORIGIN_CHANGED: 'Ahora se gestiona como parte de una reclamación',
    REVIEW_STARTED: 'El vendedor empezó la revisión',
    INFORMATION_REQUESTED: 'El vendedor pidió información',
    INFORMATION_ANSWERED: 'El comprador respondió',
    REJECTED: 'Rechazada',
    APPROVED: 'Aprobada',
    METHOD_CHOSEN: 'Método de retorno elegido',
    INSPECTION_STARTED: 'La mercancía llegó al vendedor: inspección de 24 h',
    PROBLEM_REPORTED: 'El vendedor reportó un problema',
    REFUND_REQUESTED: 'Reembolso solicitado',
    REFUND_RETRY_SCHEDULED: 'El reembolso no se completó; se reintentará',
    REFUND_GAVE_UP: 'El reembolso quedó para revisión manual',
    FINISHED: 'Reembolso completado'
};

export const ETIQUETA_ACTOR: Record<string, string> = {
    BUYER: 'Comprador',
    SELLER: 'Vendedor',
    SYSTEM: 'Sistema',
    LOGISTICS: 'Logística'
};

export interface LineaElegible {
    orderItemId: number;
    productId: number;
    productName: string;
    quantity: number;
    unitPrice: number;
    refundAmount: number;
    eligible: boolean;
    ineligibleCode: string | null;
    ineligibleMessage: string | null;
    returnWindowEndsAt: string | null;
    existingReturnId: number | null;
    existingReturnStatus: EstadoDevolucion | null;
}

export interface PedidoElegible {
    orderId: number;
    deliveredAt: string | null;
    lines: LineaElegible[];
}

export interface SolicitudDevolucion {
    orderId: number;
    orderItemId: number;
    reason: string;
    description: string;
}

export interface DevolucionSolicitada {
    id: number;
    orderItemId: number;
    status: EstadoDevolucion;
    duplicate: boolean;
    evidenceCount: number;
    createdAt: string;
}

export interface ResumenDevolucion {
    id: number;
    orderId: number;
    orderItemId: number;
    productName: string;
    quantity: number;
    refundAmount: number;
    status: EstadoDevolucion;
    reasonLabel: string;
    origin: 'BUYER' | 'CLAIM';
    createdAt: string;
    updatedAt: string;
    sellerDecisionOverdue: boolean;
    methodSelectionOverdue: boolean;
    awaitingBuyerResponse: boolean;
    pickupBlocked: boolean;
}

export interface SolicitudInformacionDevolucion {
    id: number;
    message: string;
    requestedAt: string;
    dueAt: string;
    status: 'OPEN' | 'ANSWERED';
    expired: boolean;
    responseText: string | null;
    respondedAt: string | null;
}

export interface EvidenciaDevolucion {
    ordinal: number;
    fileName: string;
    contentType: string;
    sizeBytes: number;
}

export interface EventoDevolucion {
    type: string;
    from: EstadoDevolucion | null;
    to: EstadoDevolucion | null;
    actor: string;
    details: string | null;
    at: string;
}

export interface DetalleDevolucion {
    id: number;
    orderId: number;
    orderItemId: number;
    productId: number;
    productName: string;
    quantity: number;
    unitPrice: number;
    refundAmount: number;
    status: EstadoDevolucion;
    reason: string;
    reasonLabel: string;
    description: string;
    origin: 'BUYER' | 'CLAIM';
    originClaimId: number | null;
    createdAt: string;
    updatedAt: string;
    returnWindowEndsAt: string | null;
    decision: { note: string | null; decidedAt: string } | null;
    returnMethodCode: string | null;
    inspectionDueAt: string | null;
    problemReported: boolean;
    problemDescription: string | null;
    claimId: number | null;
    pickupBlocked: boolean;
    sellerDecisionOverdue: boolean;
    methodSelectionOverdue: boolean;
    awaitingBuyerResponse: boolean;
    informationRequests: SolicitudInformacionDevolucion[];
    evidences: EvidenciaDevolucion[];
    timeline: EventoDevolucion[];
}

export interface MetodoRetorno {
    code: string;
    label: string;
}

/** Cuerpo de error de la API; {@code details.field} indica el campo del formulario al que corresponde. */
export interface ErrorDevolucion {
    status?: number;
    message?: string;
    code?: string;
    details?: { field?: string; orderItemId?: number } | null;
}

export const MAX_IMAGENES = 3;
export const MAX_BYTES_IMAGEN = 5 * 1024 * 1024;

/** Horas de plazo que tiene el comprador para responder y el vendedor para inspeccionar. */
export const HORAS_PLAZO = 24;
