import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
    CasoDetalle,
    CasoModeracion,
    DecisionModeracion,
    DestinoInformacion,
    EstadoCaso,
    PaginaResultados,
    ResultadoDecision,
    SolicitudInformacion
} from '../models/caso-soporte.model';

@Injectable({ providedIn: 'root' })
export class AdminSoporteService {
    private http = inject(HttpClient);
    private apiUrl = 'http://localhost:8080/api/support/moderation/cases';

    // La identidad la aporta el interceptor de autenticación (token Bearer); solo el rol SOPORTE puede llamar.

    /** Cola de casos; sin estado muestra los abiertos. */
    obtenerCola(estado?: EstadoCaso, pagina = 0, tamano = 20): Observable<PaginaResultados<CasoModeracion>> {
        let params = new HttpParams().set('page', pagina).set('size', tamano);
        if (estado) {
            params = params.set('status', estado);
        }
        return this.http.get<PaginaResultados<CasoModeracion>>(this.apiUrl, { params });
    }

    obtenerDetalle(id: number): Observable<CasoDetalle> {
        return this.http.get<CasoDetalle>(`${this.apiUrl}/${id}`);
    }

    tomarCaso(id: number): Observable<CasoModeracion> {
        return this.http.post<CasoModeracion>(`${this.apiUrl}/${id}/claim`, {});
    }

    solicitarInformacion(id: number, target: DestinoInformacion, message: string, targetUserId?: string):
        Observable<SolicitudInformacion> {
        return this.http.post<SolicitudInformacion>(`${this.apiUrl}/${id}/information-requests`,
            { target, targetUserId: targetUserId || null, message });
    }

    /** `expectedVersion` evita decidir sobre un caso que cambió desde que se consultó. */
    decidir(id: number, decision: DecisionModeracion, justification: string, expectedVersion: number):
        Observable<ResultadoDecision> {
        return this.http.post<ResultadoDecision>(`${this.apiUrl}/${id}/decisions`,
            { decision, justification, expectedVersion });
    }

    /** Remite el caso a CU-22; no suspende cuentas. */
    remitirAAdministracionDeCuentas(id: number, justification: string): Observable<{ referralId: number }> {
        return this.http.post<{ referralId: number }>(`${this.apiUrl}/${id}/referrals/account-admin`,
            { justification });
    }
}
