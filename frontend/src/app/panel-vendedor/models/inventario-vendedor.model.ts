/** Contratos de /api/seller/inventory (CU-15). */

import { EstadoProducto } from './producto-vendedor.model';

/**
 * Una fila del inventario. "stock" es lo que hay en bodega; "reserved", lo apartado por compras en curso;
 * "available", la diferencia (lo que aún se puede vender).
 */
export interface ItemInventario {
  productId: number;
  name: string;
  category: string;
  status: EstadoProducto;
  stock: number;
  reserved: number;
  available: number;
  /** Nivel mínimo; 0 significa "sin aviso". */
  minStock: number;
  lowStock: boolean;
}

export type TipoMovimiento = 'ENTRY' | 'ADJUSTMENT';

/** Una línea del historial de un producto. En un ajuste, "quantity" puede ser negativa. */
export interface MovimientoInventario {
  id: number;
  type: TipoMovimiento;
  quantity: number;
  stockAfter: number;
  reason: string | null;
  createdAt: string;
}

/** Cada carga masiva usa su propia plantilla: la de productos nuevos o la de inventario. */
export type TipoCarga = 'products' | 'stock';

/** Un error de una fila del Excel; "row" es el número de fila tal como se ve en la hoja. */
export interface ErrorFila {
  row: number;
  message: string;
}

/** Resultado de una carga: la de productos informa created y published; la de inventario, applied. */
export interface ResultadoCarga {
  created?: number;
  published?: number;
  applied?: number;
}
