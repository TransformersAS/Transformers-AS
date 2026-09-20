/** Contratos de /api/orders. */
export type EstadoPedido = 'CONFIRMED' | 'CANCELLATION_REQUESTED';
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
