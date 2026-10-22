/** Representa el contrato de catálogo que la futura API REST deberá entregar. */
export interface Producto {
  id: number;
  nombre: string;
  descripcion?: string;
  stock?: number;
  disponible?: boolean;
  marca?: string;
  imagenes?: string[];
  imagenIlustrativa?: boolean;
  caracteristicas?: { nombre: string; valor: string }[];
  variantes?: { nombre: string; precio: number; stock: number }[];
  descripcionTienda?: string;
  precio: number;
  precioAnterior?: number;
  imagen: string;
  categoria: string;
  tienda: string;
  calificacion: number;
  cantidadResenas: number;
  envioGratis: boolean;
  etiqueta?: string;
}

/** Resume una familia navegable del catálogo sin acoplarla al diseño. */
export interface Categoria {
  nombre: string;
  icono: string;
  color: string;
}
