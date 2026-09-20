import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import {
    MotivoReporte,
    ReporteDetalle,
    ReporteRadicado,
    ReporteResumen,
    SolicitudInformacion,
    SolicitudReporte
} from '../models/reporte.model';

/**
 * Reportes de contenido (CU-20). La sesión y el CSRF los aporta sessionInterceptor. También guarda qué reporte debe
 * mostrar el panel "Mis reportes", para que el botón de una publicación pueda llevar hasta uno ya existente.
 */
@Injectable({ providedIn: 'root' })
export class ReportesService {
    private readonly http = inject(HttpClient);
    private readonly url = `${API_BASE}/reports`;

    private readonly _panelAbierto = signal(false);
    private readonly _reporteSeleccionado = signal<number | null>(null);
    readonly panelAbierto = this._panelAbierto.asReadonly();
    readonly reporteSeleccionado = this._reporteSeleccionado.asReadonly();

    /** Abre "Mis reportes", directamente en el detalle de un reporte si se indica. */
    abrirPanel(reporteId: number | null = null): void {
        this._reporteSeleccionado.set(reporteId);
        this._panelAbierto.set(true);
    }

    cerrarPanel(): void {
        this._panelAbierto.set(false);
        this._reporteSeleccionado.set(null);
    }

    motivos(): Observable<MotivoReporte[]> {
        return this.http.get<MotivoReporte[]>(`${this.url}/reasons`);
    }

    /** Sin imágenes va como JSON; con imágenes, como formulario multipart (parte "evidences"). */
    radicar(solicitud: SolicitudReporte, imagenes: File[]): Observable<ReporteRadicado> {
        if (imagenes.length === 0) {
            return this.http.post<ReporteRadicado>(this.url, solicitud);
        }
        const formulario = new FormData();
        formulario.append('contentType', solicitud.contentType);
        formulario.append('contentId', solicitud.contentId);
        formulario.append('reason', solicitud.reason);
        formulario.append('description', solicitud.description);
        imagenes.forEach((imagen) => formulario.append('evidences', imagen, imagen.name));
        return this.http.post<ReporteRadicado>(this.url, formulario);
    }

    mios(): Observable<ReporteResumen[]> {
        return this.http.get<ReporteResumen[]>(`${this.url}/mine`);
    }

    detalle(id: number): Observable<ReporteDetalle> {
        return this.http.get<ReporteDetalle>(`${this.url}/${id}`);
    }

    responder(reporteId: number, solicitudId: number, texto: string): Observable<SolicitudInformacion> {
        return this.http.post<SolicitudInformacion>(
            `${this.url}/${reporteId}/information-requests/${solicitudId}/response`, { text: texto });
    }

    /** URL de una imagen de evidencia; el navegador la pide con la cookie de sesión y la cachea con ETag. */
    urlEvidencia(reporteId: number, ordinal: number): string {
        return `${this.url}/${reporteId}/evidences/${ordinal}`;
    }
}
