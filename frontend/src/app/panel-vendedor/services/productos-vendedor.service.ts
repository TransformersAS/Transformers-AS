import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
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
import { TIENDA_PROVISIONAL_ID } from './pedidos-vendedor.service';

/**
 * Productos de la tienda del vendedor (CU-14). Usa la misma tienda provisional (X-Store-Id) que los pedidos
 * recibidos; la sesión y el CSRF los aporta sessionInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class ProductosVendedorService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/seller/products`;
  private readonly headers = new HttpHeaders({ 'X-Store-Id': String(TIENDA_PROVISIONAL_ID) });

  buscar(texto: string, estado: EstadoProducto | ''): Observable<ProductoVendedor[]> {
    let params = new HttpParams();
    if (texto.trim()) {
      params = params.set('q', texto.trim());
    }
    if (estado) {
      params = params.set('status', estado);
    }
    return this.http.get<ProductoVendedor[]>(this.url, { params, headers: this.headers });
  }

  crear(datos: SolicitudProducto): Observable<ProductoVendedor> {
    return this.http.post<ProductoVendedor>(this.url, datos, { headers: this.headers });
  }

  actualizar(id: number, datos: SolicitudProducto): Observable<ProductoVendedor> {
    return this.http.put<ProductoVendedor>(`${this.url}/${id}`, datos, { headers: this.headers });
  }

  /** Publicar, pausar, reactivar, retirar o duplicar: todas son un POST sin cuerpo. */
  ejecutar(id: number, accion: AccionProducto): Observable<ProductoVendedor> {
    return this.http.post<ProductoVendedor>(`${this.url}/${id}/${accion}`, {}, { headers: this.headers });
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
