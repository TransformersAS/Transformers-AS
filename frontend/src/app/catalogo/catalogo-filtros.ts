import { Producto } from './models/producto.model';

export function normalizar(texto: string): string {
  return texto.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').trim();
}

export function categoriaComparable(categoria: string): string {
  const valor = normalizar(categoria);
  return valor === 'ropa' ? 'moda' : valor;
}

export function filtrarCatalogo(productos: Producto[], busqueda: string, categoria: string,
                                minimo: number | null, maximo: number | null, orden: string): Producto[] {
  const palabras = normalizar(busqueda).split(/\s+/).filter(Boolean);
  const resultado = productos.filter(p => {
    const texto = normalizar(`${p.nombre} ${p.descripcion ?? ''} ${p.categoria}`);
    return palabras.every(palabra => texto.includes(palabra))
      && (!categoria || categoriaComparable(p.categoria) === categoriaComparable(categoria))
      && (minimo === null || p.precio >= minimo)
      && (maximo === null || p.precio <= maximo);
  });
  switch (orden) {
    case 'precio-asc': return resultado.sort((a, b) => a.precio - b.precio || a.id - b.id);
    case 'precio-desc': return resultado.sort((a, b) => b.precio - a.precio || a.id - b.id);
    case 'nombre': return resultado.sort((a, b) => a.nombre.localeCompare(b.nombre, 'es'));
    case 'recientes': return resultado.sort((a, b) => b.id - a.id);
    default: return resultado;
  }
}
