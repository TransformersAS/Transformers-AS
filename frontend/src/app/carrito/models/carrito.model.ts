/** Línea mínima del carrito, preparada para sincronizarse con una sesión autenticada. */
export interface ItemCarrito {
  id: number;
  productoId: number;
  nombreProducto: string;
  cantidad: number;
  precioUnitario: number;
  subtotal: number;
}

export interface Carrito {
  cartId: number;
  items: ItemCarrito[];
  total: number;
}