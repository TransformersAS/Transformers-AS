import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import {
  Aclaracion, EstadoMiTienda, EvaluacionTienda, ResultadoFinalizacion, ResumenSuspension, Revision, Suspension,
  TiendaConAntecedentes
} from '../models/suspensiones.model';

/**
 * Evaluación y suspensión de tiendas por el administrador (CU-27). El administrador responsable lo toma el backend de
 * la sesión; aquí nunca se envía.
 */
@Injectable({ providedIn: 'root' })
export class EvaluacionTiendasService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/admin/store-evaluations`;
  private readonly suspensiones = `${API_BASE}/admin/store-suspensions`;

  listar(): Observable<TiendaConAntecedentes[]> {
    return this.http.get<TiendaConAntecedentes[]>(this.url);
  }

  detalle(tiendaId: number): Observable<EvaluacionTienda> {
    return this.http.get<EvaluacionTienda>(`${this.url}/${tiendaId}`);
  }

  pedirAclaracion(tiendaId: number, message: string): Observable<Aclaracion> {
    return this.http.post<Aclaracion>(`${this.url}/${tiendaId}/clarifications`, { message });
  }

  registrarSinMedida(tiendaId: number, note: string): Observable<Revision> {
    return this.http.post<Revision>(`${this.url}/${tiendaId}/reviews`, { note });
  }

  resumen(tiendaId: number, reason: string, durationDays: number | null): Observable<ResumenSuspension> {
    return this.http.post<ResumenSuspension>(`${this.url}/${tiendaId}/suspensions/preview`, { reason, durationDays });
  }

  suspender(tiendaId: number, reason: string, durationDays: number | null): Observable<Suspension> {
    return this.http.post<Suspension>(`${this.url}/${tiendaId}/suspensions`, { reason, durationDays });
  }

  cambiarDuracion(suspensionId: number, durationDays: number | null, justification: string): Observable<Suspension> {
    return this.http.put<Suspension>(`${this.suspensiones}/${suspensionId}/duration`, { durationDays, justification });
  }

  finalizar(suspensionId: number, reason: string): Observable<ResultadoFinalizacion> {
    return this.http.post<ResultadoFinalizacion>(`${this.suspensiones}/${suspensionId}/lift`, { reason });
  }
}

/** Estado de la tienda del vendedor y sus aclaraciones (CU-27). Sin X-Store-Id: la tienda es la de la cuenta. */
@Injectable({ providedIn: 'root' })
export class EstadoTiendaService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/seller/store/standing`;

  consultar(): Observable<EstadoMiTienda> {
    return this.http.get<EstadoMiTienda>(this.url);
  }

  responder(aclaracionId: number, response: string): Observable<Aclaracion> {
    return this.http.post<Aclaracion>(`${this.url}/clarifications/${aclaracionId}/response`, { response });
  }
}
