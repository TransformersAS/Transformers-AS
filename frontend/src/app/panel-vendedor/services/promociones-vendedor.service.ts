import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import { Promocion, SolicitudPromocion } from '../models/promocion-vendedor.model';

/**
 * Cupones de la tienda del vendedor (CU-16). No envía X-Store-Id: el backend resuelve la tienda a partir de la cuenta
 * autenticada (CU-18); la sesión y el CSRF los aporta sessionInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class PromocionesVendedorService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/seller/promotions`;

  listar(): Observable<Promocion[]> {
    return this.http.get<Promocion[]>(this.url);
  }

  crear(datos: SolicitudPromocion): Observable<Promocion> {
    return this.http.post<Promocion>(this.url, datos);
  }

  actualizar(id: number, datos: SolicitudPromocion): Observable<Promocion> {
    return this.http.put<Promocion>(`${this.url}/${id}`, datos);
  }

  activar(id: number): Observable<Promocion> {
    return this.http.post<Promocion>(`${this.url}/${id}/activate`, {});
  }

  desactivar(id: number): Observable<Promocion> {
    return this.http.post<Promocion>(`${this.url}/${id}/deactivate`, {});
  }

  eliminar(id: number): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`);
  }
}
