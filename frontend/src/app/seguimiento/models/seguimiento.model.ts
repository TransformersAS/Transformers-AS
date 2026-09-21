/** Contratos del seguimiento logístico (CU-24 pedidos, CU-25 devoluciones). */

/** Resultado de pedirle al backend que vuelva a consultar al servicio logístico. */
export type ResultadoConsulta = 'UPDATED' | 'NO_CHANGES' | 'UNAVAILABLE' | 'THROTTLED' | 'NOT_TRACKED';

/**
 * APPLIED movió el estado; RECORDED es información adicional; OUT_OF_ORDER llegó fuera de orden y se conserva solo
 * para trazabilidad (no retrocede el estado).
 */
export type ResultadoEvento = 'APPLIED' | 'RECORDED' | 'OUT_OF_ORDER';

export interface EvidenciaEntrega {
    type: string | null;
    reference: string | null;
}

export interface EventoSeguimiento {
    id: number;
    type: string;
    occurredAt: string;
    receivedAt: string;
    source: 'WEBHOOK' | 'POLLING' | 'SYSTEM';
    outcome: ResultadoEvento;
    description: string | null;
    location: string | null;
    evidence: EvidenciaEntrega | null;
}

/** GET /api/orders/{id}/tracking y /api/seller/orders/{id}/tracking. */
export interface SeguimientoPedido {
    orderId: number;
    status: string;
    /** Ausente mientras el pedido no tiene envío. */
    trackingCode?: string;
    /** false cuando el envío ya terminó: el marketplace deja de consultarlo. */
    tracking: boolean;
    /** true si la última consulta al servicio logístico falló: se muestra el último seguimiento conocido. */
    lastPollFailed: boolean;
    lastPolledAt?: string;
    refresh?: ResultadoConsulta;
    deliveredAt?: string;
    deliveryEvidence?: EvidenciaEntrega;
    events: EventoSeguimiento[];
}

/** GET /api/returns/{id}/tracking y /api/seller/returns/{id}/tracking. */
export interface SeguimientoDevolucion {
    returnId: number;
    status: string;
    trackingCode: string;
    failedPickups: number;
    maxFailedPickups: number;
    pickupStopped: boolean;
    canRequestNewPickup: boolean;
    pickedUpAt?: string;
    deliveredAt?: string;
    tracking: boolean;
    lastPollFailed: boolean;
    lastPolledAt?: string;
    refresh?: ResultadoConsulta;
    events: EventoSeguimiento[];
}

export type RolConsulta = 'comprador' | 'vendedor';
export type TipoSeguimiento = 'pedido' | 'devolucion';

/** Estados del pedido en los que ya hay (o debería haber) un envío que seguir. */
const ESTADOS_CON_SEGUIMIENTO = new Set([
    'READY_FOR_DISPATCH', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERY_EXCEPTION', 'DELIVERY_ATTEMPT_FAILED', 'DELIVERED',
    'RETURNED_TO_SELLER'
]);

export function estadoConSeguimiento(estado: string): boolean {
    return ESTADOS_CON_SEGUIMIENTO.has(estado);
}

/** Estados que informa el servicio logístico para un pedido (RF-116, RF-118). */
export const ETIQUETA_EVENTO_PEDIDO: Record<string, string> = {
    PICKED_UP: 'Recogido',
    IN_TRANSIT: 'En camino',
    DELIVERY_EXCEPTION: 'Novedad de entrega',
    DELIVERY_ATTEMPT_FAILED: 'Intento de entrega fallido',
    NEXT_ATTEMPT_SCHEDULED: 'Nuevo intento programado',
    DELIVERED: 'Entregado',
    RETURNED_TO_SELLER: 'Retornado al vendedor'
};

/** Estado del pedido tal como lo ve el usuario, incluidos los previos al transporte. */
export const ETIQUETA_ESTADO_ENVIO: Record<string, string> = {
    ...ETIQUETA_EVENTO_PEDIDO,
    CONFIRMED: 'Confirmado',
    IN_PREPARATION: 'En preparación',
    READY_FOR_DISPATCH: 'Listo para despacho',
    CANCELLED: 'Cancelado',
    CANCELLATION_REQUESTED: 'Cancelación solicitada'
};

/** Eventos del retorno de una devolución (RF-110): coinciden con sus estados. */
export const ETIQUETA_EVENTO_DEVOLUCION: Record<string, string> = {
    PICKUP_SCHEDULED: 'Recogida pendiente',
    PICKED_UP: 'Recogido',
    IN_TRANSIT: 'En retorno',
    INCIDENT: 'Novedad logística',
    PICKUP_FAILED: 'Recogida fallida',
    DELIVERED_TO_SELLER: 'Entregado al vendedor'
};

export const ETIQUETA_ESTADO_DEVOLUCION: Record<string, string> = {
    PICKUP_PENDING: 'Recogida pendiente',
    PICKED_UP: 'Recogido',
    IN_RETURN: 'En retorno',
    LOGISTICS_ISSUE: 'Novedad logística',
    PICKUP_FAILED: 'Recogida fallida',
    DELIVERED_TO_SELLER: 'Entregado al vendedor'
};

export const ETIQUETA_RESULTADO_EVENTO: Record<ResultadoEvento, string> = {
    APPLIED: '',
    RECORDED: 'Información adicional',
    OUT_OF_ORDER: 'Recibida fuera de orden: no cambió el estado'
};
