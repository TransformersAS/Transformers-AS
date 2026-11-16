import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import {
  AlcanceRestriccion, Cuenta, EstadoCuenta, EvaluacionCuenta, Restriccion, Rol, SolicitudInformacion
} from '../models/restricciones.model';

/**
 * Administración de cuentas y restricciones de acceso (CU-22). El administrador responsable lo toma el backend de
 * la sesión; aquí nunca se envía.
 */
@Injectable({ providedIn: 'root' })
export class AdminRestriccionesService {
  private readonly http = inject(HttpClient);
  private readonly cuentas = `${API_BASE}/admin/accounts`;
  private readonly restricciones = `${API_BASE}/admin/restrictions`;

  buscar(email: string): Observable<Cuenta> {
    return this.http.get<Cuenta>(this.cuentas, { params: new HttpParams().set('email', email) });
  }

  evaluacion(accountId: number): Observable<EvaluacionCuenta> {
    return this.http.get<EvaluacionCuenta>(`${this.cuentas}/${accountId}/evaluation`);
  }

  pedirInformacion(accountId: number, message: string): Observable<SolicitudInformacion> {
    return this.http.post<SolicitudInformacion>(`${this.cuentas}/${accountId}/information-requests`, { message });
  }

  restringir(accountId: number, scope: AlcanceRestriccion, role: Rol | null, reason: string, permanent: boolean,
    durationDays: number | null): Observable<Restriccion> {
    return this.http.post<Restriccion>(`${this.cuentas}/${accountId}/restrictions`,
      { scope, role, reason, permanent, durationDays });
  }

  cambiarDuracion(restrictionId: number, durationDays: number | null, justification: string): Observable<Restriccion> {
    return this.http.put<Restriccion>(`${this.restricciones}/${restrictionId}/duration`,
      { durationDays, justification });
  }

  hacerPermanente(restrictionId: number, justification: string): Observable<Restriccion> {
    return this.http.post<Restriccion>(`${this.restricciones}/${restrictionId}/permanent`,
      { durationDays: null, justification });
  }

  hacerTemporal(restrictionId: number, durationDays: number | null, justification: string): Observable<Restriccion> {
    return this.http.post<Restriccion>(`${this.restricciones}/${restrictionId}/temporary`,
      { durationDays, justification });
  }

  retirar(restrictionId: number, reason: string): Observable<{ restrictionId: number; status: string }> {
    return this.http.post<{ restrictionId: number; status: string }>(`${this.restricciones}/${restrictionId}/withdraw`,
      { reason });
  }
}

/** Estado de la propia cuenta y sus solicitudes de información (CU-22). Cualquier rol autenticado puede verlo. */
@Injectable({ providedIn: 'root' })
export class EstadoCuentaService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/account`;

  consultar(): Observable<EstadoCuenta> {
    return this.http.get<EstadoCuenta>(`${this.url}/standing`);
  }

  responder(requestId: number, response: string): Observable<SolicitudInformacion> {
    return this.http.post<SolicitudInformacion>(`${this.url}/information-requests/${requestId}/answer`, { response });
  }
}
