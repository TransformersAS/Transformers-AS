/** Contratos de /api/seller/orders (CU-23). */

export type EstadoPedido =
    | 'CONFIRMED'
    | 'IN_PREPARATION'
    | 'READY_FOR_DISPATCH'
    | 'PICKED_UP'
    | 'IN_TRANSIT'
    | 'DELIVERED'
    | 'DELIVERY_EXCEPTION'
    | 'DELIVERY_ATTEMPT_FAILED'
    | 'RETURNED_TO_SELLER'
    | 'CANCELLED'
    | 'CANCELLATION_REQUESTED';

export type EstadoPagoPedido = 'APPROVED' | 'REFUND_PENDING' | 'REFUNDED';

export type TipoNovedad = 'INVENTORY_INCONSISTENCY' | 'DAMAGED_PRODUCT' | 'OTHER';

export type MotivoCancelacion = 'OUT_OF_STOCK' | 'PRODUCT_DAMAGED' | 'OTHER';

export interface PaginaPedidos<T> {
    content: T[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

export interface PedidoResumen {
    id: number;
    status: EstadoPedido;
    paymentStatus: EstadoPagoPedido;
    total: number;
    shippingMethod: string;
    itemCount: number;
    createdAt: string;
}

export interface EntregaPedido {
    recipientName: string;
    street: string;
    city: string;
    department: string;
    postalCode: string | null;
    phone: string;
}

export interface LineaPedido {
    productId: number;
    name: string;
    quantity: number;
    unitPrice: number;
    subtotal: number;
    /** Stock físico actual; null si el producto ya no existe. */
    currentStock: number | null;
    inventoryConsistent: boolean;
}

export interface EventoHistorial {
    fromStatus: EstadoPedido | null;
    toStatus: EstadoPedido;
    actorType: 'SELLER' | 'BUYER' | 'SYSTEM' | 'LOGISTICS';
    actorId: number | null;
    reason: string | null;
    correlationId: string;
    createdAt: string;
}

export interface EnvioPedido {
    shipmentId: string;
    trackingCode: string;
    status: string;
    createdAt: string;
}

export interface NovedadPedido {
    id: number;
    type: TipoNovedad;
    description: string;
    status: 'OPEN' | 'RESOLVED';
    createdAt: string;
}

export interface PedidoDetalle {
    id: number;
    status: EstadoPedido;
    paymentStatus: EstadoPagoPedido;
    total: number;
    createdAt: string;
    shippingMethod: string;
    delivery: EntregaPedido;
    items: LineaPedido[];
    history: EventoHistorial[];
    shipment: EnvioPedido | null;
    openIssues: NovedadPedido[];
}

/** Resultado del envío pedido al proveedor logístico: FAILED no deshace el estado del pedido (A5). */
export interface ResultadoEnvio {
    status: 'CREATED' | 'FAILED';
    shipmentId?: string;
    trackingCode?: string;
    message?: string;
}

export interface ResultadoListoParaDespacho {
    orderId: number;
    status: EstadoPedido;
    paymentStatus: EstadoPagoPedido;
    shipment: ResultadoEnvio;
}

export interface ResultadoCambioEstado {
    orderId: number;
    status: EstadoPedido;
    paymentStatus: EstadoPagoPedido;
}

export interface ResultadoCancelacion extends ResultadoCambioEstado {
    refund: { status: string; message: string };
}

export interface FiltroPedidos {
    /** Sin estados el backend devuelve los pedidos por atender (CONFIRMED e IN_PREPARATION). */
    estados?: EstadoPedido[];
    pedidoId?: number | null;
    pagina?: number;
    tamano?: number;
}

/** Error de negocio del backend: {status, error, message, path} más `code` y, a veces, `details`. */
export interface ErrorApi {
    status?: number;
    message?: string;
    code?: string;
    details?: unknown;
}
