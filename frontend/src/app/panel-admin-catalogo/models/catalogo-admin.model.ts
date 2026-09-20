/** Contratos de la API de administración del catálogo (CU-17). */

/** Categoría con sus subcategorías, tal como la devuelve GET /api/admin/categories. */
export interface NodoCategoria {
  id: number;
  name: string;
  active: boolean;
  children: NodoCategoria[];
}

/** Categoría aplanada para mostrarla en una lista con sangría según su nivel. */
export interface FilaCategoria {
  id: number;
  name: string;
  active: boolean;
  parentId: number | null;
  nivel: number;
}

export interface Marca {
  id: number;
  name: string;
  active: boolean;
}

export interface ValorAtributo {
  id: number;
  value: string;
}

export interface Atributo {
  id: number;
  name: string;
  values: ValorAtributo[];
}
