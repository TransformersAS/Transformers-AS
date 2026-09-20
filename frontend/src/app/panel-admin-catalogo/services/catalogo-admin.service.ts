import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import { Atributo, Marca, NodoCategoria } from '../models/catalogo-admin.model';

/** Llamadas a /api/admin/**: solo responden con una sesión de administrador (el backend lo exige). */
@Injectable({ providedIn: 'root' })
export class CatalogoAdminService {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_BASE}/admin`;

  // ---------- Categorías ----------

  obtenerCategorias(): Observable<NodoCategoria[]> {
    return this.http.get<NodoCategoria[]>(`${this.base}/categories`);
  }

  crearCategoria(name: string, parentId: number | null): Observable<unknown> {
    return this.http.post(`${this.base}/categories`, { name, parentId });
  }

  /** Cambia el nombre y/o mueve la categoría a otro padre. */
  actualizarCategoria(id: number, name: string, parentId: number | null): Observable<unknown> {
    return this.http.put(`${this.base}/categories/${id}`, { name, parentId });
  }

  cambiarEstadoCategoria(id: number, activar: boolean): Observable<unknown> {
    return this.http.post(`${this.base}/categories/${id}/${activar ? 'activate' : 'deactivate'}`, {});
  }

  eliminarCategoria(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/categories/${id}`);
  }

  // ---------- Marcas ----------

  obtenerMarcas(): Observable<Marca[]> {
    return this.http.get<Marca[]>(`${this.base}/brands`);
  }

  crearMarca(name: string): Observable<unknown> {
    return this.http.post(`${this.base}/brands`, { name });
  }

  cambiarEstadoMarca(id: number, activar: boolean): Observable<unknown> {
    return this.http.post(`${this.base}/brands/${id}/${activar ? 'activate' : 'deactivate'}`, {});
  }

  eliminarMarca(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/brands/${id}`);
  }

  // ---------- Atributos ----------

  obtenerAtributos(): Observable<Atributo[]> {
    return this.http.get<Atributo[]>(`${this.base}/attributes`);
  }

  crearAtributo(name: string): Observable<unknown> {
    return this.http.post(`${this.base}/attributes`, { name });
  }

  eliminarAtributo(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/attributes/${id}`);
  }

  agregarValor(atributoId: number, value: string): Observable<unknown> {
    return this.http.post(`${this.base}/attributes/${atributoId}/values`, { value });
  }

  eliminarValor(atributoId: number, valorId: number): Observable<unknown> {
    return this.http.delete(`${this.base}/attributes/${atributoId}/values/${valorId}`);
  }
}
