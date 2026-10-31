/** Contratos de /api/orders. */
/** Estados que el backend puede devolver; los de transporte (Recogido a Retornado al vendedor) los informa logística (CU-24). */
export type EstadoPedido =
  | 'CONFIRMED' | 'IN_PREPARATION' | 'READY_FOR_DISPATCH' | 'PICKED_UP' | 'IN_TRANSIT' | 'DELIVERED'
  | 'DELIVERY_EXCEPTION' | 'DELIVERY_ATTEMPT_FAILED' | 'RETURNED_TO_SELLER' | 'CANCELLED' | 'CANCELLATION_REQUESTED';

/** Texto para el comprador de cada estado. */
export const ETIQUETA_ESTADO_PEDIDO: Record<EstadoPedido, string> = {
  CONFIRMED: 'Confirmado',
  IN_PREPARATION: 'En preparación',
  READY_FOR_DISPATCH: 'Listo para despacho',
  PICKED_UP: 'Recogido',
  IN_TRANSIT: 'En camino',
  DELIVERED: 'Entregado',
  DELIVERY_EXCEPTION: 'Novedad en la entrega',
  DELIVERY_ATTEMPT_FAILED: 'Intento de entrega fallido',
  RETURNED_TO_SELLER: 'Retornado al vendedor',
  CANCELLED: 'Cancelado',
  CANCELLATION_REQUESTED: 'Cancelación solicitada'
};
export interface FiltrosPedidos {
  from?: string;
  to?: string;
  status?: EstadoPedido;
}
export interface Pedido {
  id: number;
  status: EstadoPedido;
  total: number;
  shippingMethod: string;
  createdAt: string;
}
export interface ItemPedido {
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
}
export type EstadoPagoPedido = 'APPROVED' | 'REFUND_PENDING' | 'REFUNDED';
export const ETIQUETA_ESTADO_PAGO: Record<EstadoPagoPedido, string> = {
  APPROVED: 'Aprobado',
  REFUND_PENDING: 'Reembolso pendiente',
  REFUNDED: 'Reembolsado'
};
export interface DireccionHistoricaPedido {
  recipientName: string | null;
  street: string | null;
  city: string | null;
  department: string | null;
  postalCode: string | null;
  phone: string | null;
}
export interface CambioEstadoPedido {
  fromStatus: EstadoPedido | null;
  toStatus: EstadoPedido;
  /** Fecha de registro; desconocida para entradas incorporadas por migración. */
  createdAt: string | null;
  legacy: boolean;
}
export interface DetallePedido extends Pedido {
  history: CambioEstadoPedido[];
  delivery: DireccionHistoricaPedido | null;
  paymentStatus: EstadoPagoPedido | null;
  storeId: number | null;
  discountAmount: number | null;
  shippingCost: number | null;
  sellerNameSnapshot: string | null;
  addressId: number | null;
  transactionId: string | null;
  items: ItemPedido[];
}

export type MotivoCancelacion = 'CHANGED_MIND' | 'OTHER';
export interface CancelacionPedido {
  reasonCode: MotivoCancelacion;
  details?: string;
}
export interface ResultadoCancelacion {
  orderId: number;
  status: 'CANCELLED';
  paymentStatus: EstadoPagoPedido;
  refund: { status: 'NOT_APPLICABLE' | 'COMPLETED' | 'PENDING' | 'FAILED'; message: string };
}
