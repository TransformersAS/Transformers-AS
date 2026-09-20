import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import { TIENDA_PROVISIONAL_ID } from '../../panel-vendedor/services/pedidos-vendedor.service';
import { RolConsulta, SeguimientoDevolucion, SeguimientoPedido } from '../models/seguimiento.model';

/**
 * Seguimiento logístico de pedidos (CU-24) y devoluciones (CU-25). Solo consulta: los estados los informa el servicio
 * logístico y nadie los cambia a mano; «actualizar» pide al backend que vuelva a consultarlo. El vendedor envía la
 * tienda provisional (X-Store-Id) hasta que CU-18 la resuelva desde la sesión.
 */
@Injectable({ providedIn: 'root' })
export class SeguimientoService {
    private readonly http = inject(HttpClient);
    private readonly cabeceraTienda = new HttpHeaders({ 'X-Store-Id': String(TIENDA_PROVISIONAL_ID) });

    pedido(id: number, rol: RolConsulta, actualizar = false): Observable<SeguimientoPedido> {
        const base = rol === 'vendedor' ? `${API_BASE}/seller/orders/${id}/tracking` : `${API_BASE}/orders/${id}/tracking`;
        return this.llamar<SeguimientoPedido>(base, rol, actualizar);
    }

    devolucion(id: number, rol: RolConsulta, actualizar = false): Observable<SeguimientoDevolucion> {
        const base = rol === 'vendedor' ? `${API_BASE}/seller/returns/${id}/tracking` : `${API_BASE}/returns/${id}/tracking`;
        return this.llamar<SeguimientoDevolucion>(base, rol, actualizar);
    }

    private llamar<T>(base: string, rol: RolConsulta, actualizar: boolean): Observable<T> {
        const headers = rol === 'vendedor' ? this.cabeceraTienda : undefined;
        return actualizar
            ? this.http.post<T>(`${base}/refresh`, {}, { headers })
            : this.http.get<T>(base, { headers });
    }
}
