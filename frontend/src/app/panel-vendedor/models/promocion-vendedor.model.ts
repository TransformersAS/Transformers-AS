/** Contratos de /api/seller/promotions (CU-16). */

export type TipoDescuento = 'PERCENT' | 'AMOUNT';

/** INACTIVE apagado · SCHEDULED activo pero aún no empieza · RUNNING se puede usar ya · EXPIRED ya terminó. */
export type EstadoPromocion = 'INACTIVE' | 'SCHEDULED' | 'RUNNING' | 'EXPIRED';

export interface Promocion {
  id: number;
  name: string;
  code: string;
  discountType: TipoDescuento;
  discountValue: number;
  minPurchase: number | null;
  startsAt: string;
  endsAt: string;
  active: boolean;
  status: EstadoPromocion;
  /** Vacío: aplica a todos los productos de la tienda. */
  productIds: number[];
  /** Otros cupones activos que se cruzan en fechas y productos. Es un aviso, no impide guardar. */
  conflicts: string[];
}

export interface SolicitudPromocion {
  name: string;
  code: string;
  discountType: TipoDescuento;
  discountValue: number;
  minPurchase: number | null;
  startsAt: string;
  endsAt: string;
  productIds: number[];
}
