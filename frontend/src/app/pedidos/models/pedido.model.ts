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
export interface DetallePedido extends Pedido {
  addressId: number | null;
  transactionId: string | null;
  items: ItemPedido[];
}
