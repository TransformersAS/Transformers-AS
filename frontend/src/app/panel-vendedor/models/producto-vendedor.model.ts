/** Contratos de /api/seller/products (CU-14). */

export type EstadoProducto = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'RETIRED';

/** Acciones que cambian el estado de una publicación o la copian. */
export type AccionProducto = 'publish' | 'pause' | 'reactivate' | 'retire' | 'duplicate';

export interface VarianteProducto {
  name: string;
  price: number;
  stock: number;
}

/** Publicación tal como la devuelve el backend; también sirve como vista previa. */
export interface ProductoVendedor {
  id: number;
  name: string;
  description: string | null;
  price: number;
  stock: number;
  category: string;
  brandId: number | null;
  status: EstadoProducto;
  imageUrls: string[];
  attributeValueIds: number[];
  variants: VarianteProducto[];
}

/** Datos que se envían para crear o editar (el estado lo decide el backend). */
export interface SolicitudProducto {
  name: string;
  description: string;
  price: number;
  stock: number;
  category: string;
  brandId: number | null;
  imageUrls: string[];
  attributeValueIds: number[];
  variants: VarianteProducto[];
}
