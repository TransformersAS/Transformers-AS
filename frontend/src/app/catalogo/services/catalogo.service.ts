import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { Categoria, Producto } from '../models/producto.model';

/** Aísla los datos demo para reemplazar `of(...)` por HttpClient sin tocar componentes. */
@Injectable({ providedIn: 'root' })
export class CatalogoService {
  /** Devuelve el surtido destacado que ocupará la portada. */
  obtenerDestacados(): Observable<Producto[]> {
    return of([
      { id: 'aud-01', nombre: 'Auriculares Nova Wave', precio: 189900, precioAnterior: 249900, imagen: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=700&q=85', categoria: 'Tecnología', tienda: 'Norte Studio', calificacion: 4.8, cantidadResenas: 124, envioGratis: true, etiqueta: '-24%' },
      { id: 'hog-02', nombre: 'Lámpara Orbital Mini', precio: 129900, imagen: 'https://images.unsplash.com/photo-1507473885765-e6ed057f782c?auto=format&fit=crop&w=700&q=85', categoria: 'Hogar', tienda: 'Casa Nómada', calificacion: 4.9, cantidadResenas: 88, envioGratis: true, etiqueta: 'Hecho local' },
      { id: 'mod-03', nombre: 'Bolso Camino Arena', precio: 159900, precioAnterior: 199900, imagen: 'https://images.unsplash.com/photo-1584917865442-de89df76afd3?auto=format&fit=crop&w=700&q=85', categoria: 'Moda', tienda: 'Línea Clara', calificacion: 4.7, cantidadResenas: 53, envioGratis: false },
      { id: 'dep-04', nombre: 'Botella Térmica 750 ml', precio: 79900, imagen: 'https://images.unsplash.com/photo-1602143407151-7111542de6e8?auto=format&fit=crop&w=700&q=85', categoria: 'Deportes', tienda: 'Monte Abierto', calificacion: 4.9, cantidadResenas: 207, envioGratis: true, etiqueta: 'Más vendido' }
    ]);
  }

  /** Expone categorías para que una API futura pueda personalizarlas por usuario. */
  obtenerCategorias(): Observable<Categoria[]> {
    return of([
      { nombre: 'Tecnología', icono: 'headset-outline', color: '#ffd86b' },
      { nombre: 'Moda', icono: 'shirt-outline', color: '#f9aa9c' },
      { nombre: 'Hogar', icono: 'home-outline', color: '#c8e65a' },
      { nombre: 'Deportes', icono: 'bicycle-outline', color: '#9edbe6' },
      { nombre: 'Belleza', icono: 'sparkles-outline', color: '#d7b8f5' }
    ]);
  }
}
