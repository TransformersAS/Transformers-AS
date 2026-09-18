/** Estado visible de una compra, independiente de la representación que use Spring Boot. */
export type EstadoPedido = 'confirmado' | 'enviado' | 'entregado' | 'cancelado';
export interface Pedido { id: string; estado: EstadoPedido; creadoEn: string; total: number; }
