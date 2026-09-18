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
    'http://localhost:8080/api/products';

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
              this.obtenerImagen(producto.category),

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

  private obtenerImagen(categoria: string): string {

    switch (categoria.toLowerCase()) {

      case 'tecnología':
        return 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=700&q=85';

      case 'hogar':
        return 'https://images.unsplash.com/photo-1507473885765-e6ed057f782c?auto=format&fit=crop&w=700&q=85';

      case 'moda':
        return 'https://images.unsplash.com/photo-1584917865442-de89df76afd3?auto=format&fit=crop&w=700&q=85';

      default:
        return 'https://images.unsplash.com/photo-1523275335684-37898b6baf30e?auto=format&fit=crop&w=700&q=85';
    }
  }
}
