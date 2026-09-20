import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import { ConfiguracionTienda, ImagenTienda, SolicitudTienda, TipoImagen } from '../models/mi-tienda.model';

/**
 * Configuración de la tienda del vendedor (CU-18). La sesión y el CSRF los aporta sessionInterceptor. No envía
 * X-Store-Id: el backend resuelve la tienda a partir de la cuenta autenticada.
 */
@Injectable({ providedIn: 'root' })
export class VendedorService {
    private readonly http = inject(HttpClient);
    private readonly url = `${API_BASE}/seller/store`;

    obtener(): Observable<ConfiguracionTienda> {
        return this.http.get<ConfiguracionTienda>(this.url);
    }

    /** Aplica las mismas validaciones que guardar y devuelve cómo quedaría, sin guardar nada. */
    previsualizar(solicitud: SolicitudTienda): Observable<ConfiguracionTienda> {
        return this.http.post<ConfiguracionTienda>(`${this.url}/preview`, solicitud);
    }

    /** `version` es la que devolvió la consulta: si otra edición se adelantó, el backend responde 409. */
    guardar(solicitud: SolicitudTienda, version: number): Observable<ConfiguracionTienda> {
        return this.http.put<ConfiguracionTienda>(this.url, { ...solicitud, version });
    }

    /** Sube o reemplaza una imagen (multipart, parte "file"). El Content-Type lo fija el navegador con su boundary. */
    subirImagen(tipo: TipoImagen, archivo: File): Observable<ImagenTienda> {
        const formulario = new FormData();
        formulario.append('file', archivo, archivo.name);
        return this.http.put<ImagenTienda>(`${this.url}/images/${this.ruta(tipo)}`, formulario);
    }

    quitarImagen(tipo: TipoImagen): Observable<void> {
        return this.http.delete<void>(`${this.url}/images/${this.ruta(tipo)}`);
    }

    private ruta(tipo: TipoImagen): string {
        return tipo.toLowerCase();
    }
}
