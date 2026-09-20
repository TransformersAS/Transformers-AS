import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import { TIENDA_PROVISIONAL_ID } from '../../panel-vendedor/services/pedidos-vendedor.service';
import {
  DecisionSoporte,
  EstadoReclamacion,
  NuevaReclamacion,
  Reclamacion
} from '../models/reclamacion.model';

/**
 * Reclamaciones de compra (CU-13). Cada rol usa su propia entrada del backend: el comprador /api/claims, el
 * vendedor /api/seller/claims (con la tienda provisional, igual que sus pedidos) y soporte /api/support/claims.
 * La sesión y el CSRF los aporta sessionInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class ReclamacionesService {
  private readonly http = inject(HttpClient);
  private readonly comprador = `${API_BASE}/claims`;
  private readonly vendedor = `${API_BASE}/seller/claims`;
  private readonly soporte = `${API_BASE}/support/claims`;
  private readonly cabeceraTienda = new HttpHeaders({ 'X-Store-Id': String(TIENDA_PROVISIONAL_ID) });

  // ---------- Comprador ----------

  listarMias(): Observable<Reclamacion[]> {
    return this.http.get<Reclamacion[]>(this.comprador);
  }

  abrir(datos: NuevaReclamacion): Observable<Reclamacion> {
    return this.http.post<Reclamacion>(this.comprador, datos);
  }

  escribir(id: number, message: string): Observable<Reclamacion> {
    return this.http.post<Reclamacion>(`${this.comprador}/${id}/messages`, { message });
  }

  aceptarSolucion(id: number): Observable<Reclamacion> {
    return this.http.post<Reclamacion>(`${this.comprador}/${id}/accept`, {});
  }

  escalar(id: number, reason: string): Observable<Reclamacion> {
    return this.http.post<Reclamacion>(`${this.comprador}/${id}/escalate`, { reason });
  }

  // ---------- Vendedor ----------

  listarDeLaTienda(): Observable<Reclamacion[]> {
    return this.http.get<Reclamacion[]>(this.vendedor, { headers: this.cabeceraTienda });
  }

  pedirInformacion(id: number, message: string): Observable<Reclamacion> {
    return this.http.post<Reclamacion>(`${this.vendedor}/${id}/request-info`, { message },
      { headers: this.cabeceraTienda });
  }

  proponerSolucion(id: number, message: string, refundAmount: number | null): Observable<Reclamacion> {
    return this.http.post<Reclamacion>(`${this.vendedor}/${id}/propose`, { message, refundAmount },
      { headers: this.cabeceraTienda });
  }

  // ---------- Soporte ----------

  listarParaSoporte(estado: EstadoReclamacion): Observable<Reclamacion[]> {
    return this.http.get<Reclamacion[]>(this.soporte, { params: new HttpParams().set('status', estado) });
  }

  decidir(id: number, decision: DecisionSoporte, amount: number | null, note: string): Observable<Reclamacion> {
    return this.http.post<Reclamacion>(`${this.soporte}/${id}/resolve`, { decision, amount, note });
  }
}
