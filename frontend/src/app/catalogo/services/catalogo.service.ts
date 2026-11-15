import { API_BASE } from '../../core/config/api.config';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of, BehaviorSubject } from 'rxjs';
import { Categoria, Producto } from '../models/producto.model';

interface ProductoApi {
  id: number;
  name: string;
  description?: string;
  price: number;
  stock: number;
  category: string;
  active: boolean;
  ratingAverage?: number;
  reviewCount?: number;
  brand?: string;
  store?: { id: number; name: string; description?: string };
  imageUrls?: string[];
  specifications?: { name: string; value: string }[];
  variants?: { name: string; price: number; stock: number }[];
}

@Injectable({ providedIn: 'root' })
export class CatalogoService {
  readonly actualizaciones = new BehaviorSubject(0);
  refrescarValoraciones(){this.actualizaciones.next(this.actualizaciones.value+1);}

  private readonly http = inject(HttpClient);

  private readonly apiUrl =
    `${API_BASE}/products`;

  obtenerDestacados(): Observable<Producto[]> {

    return this.http.get<ProductoApi[]>(this.apiUrl).pipe(

      map((productos) =>
        productos
          .filter((producto) => producto.active)

          .map(producto => this.convertirProducto(producto))
      )
    );
  }

  obtenerDetalle(id: number): Observable<Producto> {
    return this.http.get<ProductoApi>(`${this.apiUrl}/${id}`).pipe(
      map(producto => this.convertirProducto(producto))
    );
  }

  private convertirProducto(producto: ProductoApi): Producto {
    return {
      id: producto.id,
      nombre: producto.name,
      descripcion: producto.description,
      stock: producto.stock,
      disponible: producto.active,
      precio: producto.price,
      imagen: producto.imageUrls?.[0] || this.obtenerImagen(producto.name, producto.category),
      imagenes: producto.imageUrls?.length ? producto.imageUrls : [this.obtenerImagen(producto.name, producto.category)],
      imagenIlustrativa: !producto.imageUrls?.length,
      marca: producto.brand,
      caracteristicas: (producto.specifications ?? []).map(s => ({ nombre: s.name, valor: s.value })),
      variantes: (producto.variants ?? []).map(v => ({ nombre: v.name, precio: v.price, stock: v.stock })),
      descripcionTienda: producto.store?.description,
      categoria: producto.category,
      tienda: producto.store?.name || 'Marketplace',
      calificacion: producto.ratingAverage ?? 0,
      cantidadResenas: producto.reviewCount ?? 0,
      envioGratis: true
    };
  }

  obtenerCategorias(): Observable<Categoria[]> {

    return of([
      {
        nombre: 'Tecnología',
        icono: 'laptop-outline',
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
      return '/assets/catalogo-demo/photo-1521572163474-6864f9cf17ab.jpg';
    }

    if (producto.includes('jean')) {
      return '/assets/catalogo-demo/photo-1542272604-787c3835535d.jpg';
    }

    if (producto.includes('chaqueta')) {
      return '/assets/catalogo-demo/photo-1551028719-00167b16eac5.jpg';
    }

    if (producto.includes('mochila')) {
      return '/assets/catalogo-demo/photo-1553062407-98eeb64c6a62.jpg';
    }

    if (producto.includes('reloj') || producto.includes('smartwatch')) {
      return '/assets/catalogo-demo/photo-1523275335684-37898b6baf30.jpg';
    }

    if (producto.includes('audifonos')) {
      return '/assets/catalogo-demo/photo-1505740420928-5e560c06d30e.jpg';
    }

    if (producto.includes('teclado')) {
      return '/assets/catalogo-demo/photo-1587829741301-dc798b83add3.jpg';
    }

    if (producto.includes('mouse')) {
      return '/assets/catalogo-demo/photo-1527864550417-7fd91fc51a46.jpg';
    }

    if (producto.includes('monitor')) {
      return '/assets/catalogo-demo/photo-1527443224154-c4a3942d3acf.jpg';
    }

    if (producto.includes('webcam')) {
      return '/assets/catalogo-demo/photo-1611162617474-5b21e879e113.jpg';
    }

    if (producto.includes('lampara')) {
      return '/assets/catalogo-demo/photo-1507473885765-e6ed057f782c.jpg';
    }

    if (producto.includes('cafetera')) {
      return '/assets/catalogo-demo/photo-1495474472287-4d71bcdd2085.jpg';
    }

    if (producto.includes('bolso')) {
      return '/assets/catalogo-demo/photo-1584917865442-de89df76afd3.jpg';
    }

    if (producto.includes('botella')) {
      return '/assets/catalogo-demo/photo-1602143407151-7111542de6e8.jpg';
    }

    if (producto.includes('tenis')) {
      return '/assets/catalogo-demo/photo-1542291026-7eec264c27ff.jpg';
    }

    if (producto.includes('taza')) {
      return '/assets/catalogo-demo/photo-1514228742587-6b1558fcca3d.jpg';
    }

    if (producto.includes('libreta') || producto.includes('cuaderno')) {
      return '/assets/catalogo-demo/photo-1531346878377-a5be20888e57.jpg';
    }

    if (producto.includes('gorra')) {
      return '/assets/catalogo-demo/photo-1521369909029-2afed882baee.jpg';
    }

    if (
      producto.includes('facial') ||
      producto.includes('cuidado')
    ) {
      return '/assets/catalogo-demo/photo-1556228578-8c89e6adf883.jpg';
    }

    // Fallbacks por categoría, por si agregan nuevos productos.

    if (categoriaNormalizada === 'tecnologia') {
      return '/assets/catalogo-demo/photo-1498049794561-7780e7231661.jpg';
    }

    if (categoriaNormalizada === 'hogar') {
      return '/assets/catalogo-demo/photo-1484101403633-562f891dc89a.jpg';
    }

    if (categoriaNormalizada === 'moda' || categoriaNormalizada === 'ropa') {
      return '/assets/catalogo-demo/photo-1445205170230-053b83016050.jpg';
    }

    if (categoriaNormalizada === 'papeleria') {
      return '/assets/catalogo-demo/photo-1531346878377-a5be20888e57.jpg';
    }

    if (categoriaNormalizada === 'accesorios') {
      return '/assets/catalogo-demo/photo-1521369909029-2afed882baee.jpg';
    }

    if (categoriaNormalizada === 'deportes') {
      return '/assets/catalogo-demo/photo-1461896836934-ffe607ba8211.jpg';
    }

    if (categoriaNormalizada === 'belleza') {
      return '/assets/catalogo-demo/photo-1596462502278-27bfdc403348.jpg';
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
