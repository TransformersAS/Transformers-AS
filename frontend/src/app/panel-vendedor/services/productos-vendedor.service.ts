import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import { Atributo, Marca, NodoCategoria } from '../../panel-admin-catalogo/models/catalogo-admin.model';
import {
  AccionProducto,
  EstadoProducto,
  ProductoVendedor,
  SolicitudProducto
} from '../models/producto-vendedor.model';

/**
 * Productos de la tienda del vendedor (CU-14). No envía X-Store-Id: el backend resuelve la tienda a partir de la cuenta
 * autenticada (CU-18); la sesión y el CSRF los aporta sessionInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class ProductosVendedorService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/seller/products`;

  buscar(texto: string, estado: EstadoProducto | ''): Observable<ProductoVendedor[]> {
    let params = new HttpParams();
    if (texto.trim()) {
      params = params.set('q', texto.trim());
    }
    if (estado) {
      params = params.set('status', estado);
    }
    return this.http.get<ProductoVendedor[]>(this.url, { params });
  }

  crear(datos: SolicitudProducto): Observable<ProductoVendedor> {
    return this.http.post<ProductoVendedor>(this.url, datos);
  }

  actualizar(id: number, datos: SolicitudProducto): Observable<ProductoVendedor> {
    return this.http.put<ProductoVendedor>(`${this.url}/${id}`, datos);
  }

  /** Publicar, pausar, reactivar, retirar o duplicar: todas son un POST sin cuerpo. */
  ejecutar(id: number, accion: AccionProducto): Observable<ProductoVendedor> {
    return this.http.post<ProductoVendedor>(`${this.url}/${id}/${accion}`, {});
  }

  // ---------- Estructura del catálogo (solo lectura, CU-17) ----------

  obtenerCategorias(): Observable<NodoCategoria[]> {
    return this.http.get<NodoCategoria[]>(`${API_BASE}/categories`);
  }

  obtenerMarcas(): Observable<Marca[]> {
    return this.http.get<Marca[]>(`${API_BASE}/brands`);
  }

  obtenerAtributos(): Observable<Atributo[]> {
    return this.http.get<Atributo[]>(`${API_BASE}/attributes`);
  }
}
