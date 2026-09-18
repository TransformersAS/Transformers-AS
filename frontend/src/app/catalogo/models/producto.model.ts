/** Representa el contrato de catálogo que la futura API REST deberá entregar. */
export interface Producto {
  id: string;
  nombre: string;
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
