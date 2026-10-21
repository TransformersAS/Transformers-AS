import { API_BASE } from '../../core/config/api.config';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of } from 'rxjs';
import { Categoria, Producto } from '../models/producto.model';

interface ProductoApi {
  id: number;
  name: string;
  description?: string;
  price: number;
  stock: number;
  category: string;
  active: boolean;
}

@Injectable({ providedIn: 'root' })
export class CatalogoService {

  private readonly http = inject(HttpClient);

  private readonly apiUrl =
    `${API_BASE}/products`;

  obtenerDestacados(): Observable<Producto[]> {

    return this.http.get<ProductoApi[]>(this.apiUrl).pipe(

      map((productos) =>
        productos
          .filter((producto) => producto.active)

          .map((producto) => ({
            id: producto.id,

            nombre: producto.name,

            precio: producto.price,

            imagen:
              this.obtenerImagen(
                producto.name,
                producto.category
              ),

            categoria:
              producto.category,

            tienda:
              'Marketplace',

            calificacion:
              5,

            cantidadResenas:
              0,

            envioGratis:
              true
          }))
      )
    );
  }

  obtenerCategorias(): Observable<Categoria[]> {

    return of([
      {
        nombre: 'Tecnología',
        icono: 'headset-outline',
        color: '#ffd86b'
      },
      {
        nombre: 'Moda',
        icono: 'shirt-outline',
        color: '#f9aa9c'
      },
      {
        nombre: 'Hogar',
        icono: 'home-outline',
        color: '#c8e65a'
      },
      {
        nombre: 'Deportes',
        icono: 'bicycle-outline',
        color: '#9edbe6'
      },
      {
        nombre: 'Belleza',
        icono: 'sparkles-outline',
        color: '#d7b8f5'
      }
    ]);
  }

  private obtenerImagen(
    nombre: string,
    categoria: string
  ): string {

    const producto = nombre
      .toLowerCase()
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '');

    const categoriaNormalizada = categoria
      .toLowerCase()
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '');

    if (producto.includes('camiseta')) {
      return 'https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('jean')) {
      return 'https://images.unsplash.com/photo-1542272604-787c3835535d?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('chaqueta')) {
      return 'https://images.unsplash.com/photo-1551028719-00167b16eac5?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('mochila')) {
      return 'https://images.unsplash.com/photo-1553062407-98eeb64c6a62?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('reloj') || producto.includes('smartwatch')) {
      return 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('audifonos')) {
      return 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('teclado')) {
      return 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('mouse')) {
      return 'https://images.unsplash.com/photo-1527864550417-7fd91fc51a46?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('monitor')) {
      return 'https://images.unsplash.com/photo-1527443224154-c4a3942d3acf?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('webcam')) {
      return 'https://images.unsplash.com/photo-1611162617474-5b21e879e113?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('lampara')) {
      return 'https://images.unsplash.com/photo-1507473885765-e6ed057f782c?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('cafetera')) {
      return 'https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('bolso')) {
      return 'https://images.unsplash.com/photo-1584917865442-de89df76afd3?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('botella')) {
      return 'https://images.unsplash.com/photo-1602143407151-7111542de6e8?auto=format&fit=crop&w=700&q=85';
    }

    if (producto.includes('tenis')) {
      return 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=700&q=85';
    }

    if (
      producto.includes('facial') ||
      producto.includes('cuidado')
    ) {
      return 'https://images.unsplash.com/photo-1556228578-8c89e6adf883?auto=format&fit=crop&w=700&q=85';
    }

    // Fallbacks por categoría, por si agregan nuevos productos.

    if (categoriaNormalizada === 'tecnologia') {
      return 'https://images.unsplash.com/photo-1498049794561-7780e7231661?auto=format&fit=crop&w=700&q=85';
    }

    if (categoriaNormalizada === 'hogar') {
      return 'https://images.unsplash.com/photo-1484101403633-562f891dc89a?auto=format&fit=crop&w=700&q=85';
    }

    if (categoriaNormalizada === 'moda' || categoriaNormalizada === 'ropa') {
      return 'https://images.unsplash.com/photo-1445205170230-053b83016050?auto=format&fit=crop&w=700&q=85';
    }

    if (categoriaNormalizada === 'deportes') {
      return 'https://images.unsplash.com/photo-1461896836934-ffe607ba8211?auto=format&fit=crop&w=700&q=85';
    }

    if (categoriaNormalizada === 'belleza') {
      return 'https://images.unsplash.com/photo-1596462502278-27bfdc403348?auto=format&fit=crop&w=700&q=85';
    }

    // Imagen genérica final
    return 'data:image/svg+xml,' + encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 700 700">' +
      '<rect width="700" height="700" fill="#f3eee5"/>' +
      '<text x="350" y="350" text-anchor="middle" font-family="sans-serif" font-size="28" fill="#53676b">Imagen no disponible</text>' +
      '</svg>'
    );
  }
}
