/** Contratos de /api/seller/analytics (CU-26). Montos en pesos; fechas en formato yyyy-MM-dd. */

export interface IndicadoresVenta {
  /** Ventas: valor de los productos menos descuentos, sin envío. */
  sales: number;
  discounts: number;
  refunds: number;
  /** Ingresos netos: ventas menos reembolsos completados. */
  netRevenue: number;
  costs: number;
  profit: number;
  /** Nulo si no hay ingresos netos positivos. */
  marginPercent: number | null;
  orders: number;
  units: number;
  averageTicket: number;
  unitsWithoutCost: number;
  /** Falso si alguna unidad vendida no tiene costo: costos y ganancia son parciales. */
  costComplete: boolean;
}

export interface ProductoVendido {
  productId: number;
  name: string;
  category: string;
  units: number;
  sales: number;
  refunds: number;
  costs: number;
  profit: number;
  unitsWithoutCost: number;
}

export interface CategoriaVendida {
  category: string;
  units: number;
  sales: number;
}

export interface PuntoSerie {
  /** Primer día del grupo (día, semana o mes). */
  start: string;
  sales: number;
  costs: number;
  orders: number;
}

export interface ResultadoPeriodo {
  from: string;
  to: string;
  hasData: boolean;
  indicators: IndicadoresVenta;
  topProducts: ProductoVendido[];
  categories: CategoriaVendida[];
  series: PuntoSerie[];
}

export type IndicadorComparable =
  'sales' | 'refunds' | 'netRevenue' | 'costs' | 'profit' | 'orders' | 'units' | 'averageTicket';

export interface Comparacion {
  indicator: IndicadorComparable;
  current: number;
  previous: number;
  difference: number;
  /** Nulo cuando el periodo de comparación vale cero: no se puede calcular. */
  percentChange: number | null;
  percentAvailable: boolean;
}

export interface InformeVentas {
  storeName: string;
  productId: number | null;
  productName: string | null;
  category: string | null;
  granularity: 'DAY' | 'WEEK' | 'MONTH';
  current: ResultadoPeriodo;
  comparison: ResultadoPeriodo | null;
  comparisons: Comparacion[];
}

export interface OpcionesAnalitica {
  products: { id: number; name: string; category: string; hasCost: boolean }[];
  categories: string[];
}

/** Lo que pide el vendedor. La tienda no viaja: el backend la toma de la sesión. */
export interface ConsultaAnalitica {
  from: string;
  to: string;
  compareFrom: string | null;
  compareTo: string | null;
  productId: number | null;
  category: string | null;
}
