import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import {
    FiltroPedidos,
    MotivoCancelacion,
    NovedadPedido,
    PaginaPedidos,
    PedidoDetalle,
    PedidoResumen,
    ResultadoCambioEstado,
    ResultadoCancelacion,
    ResultadoEnvio,
    ResultadoListoParaDespacho,
    TipoNovedad
} from '../models/pedido-vendedor.model';

/**
 * Tienda provisional del vendedor. El backend la recibe en X-Store-Id porque todavía no existe la relación
 * cuenta-tienda (CU-18); cuando exista, el backend la resolverá desde la sesión y esta cabecera se elimina.
 */
export const TIENDA_PROVISIONAL_ID = 1;

/** Pedidos recibidos por la tienda del vendedor (CU-23). La sesión y el CSRF los aporta sessionInterceptor. */
@Injectable({ providedIn: 'root' })
export class PedidosVendedorService {
    private readonly http = inject(HttpClient);
    private readonly url = `${API_BASE}/seller/orders`;
    private readonly headers = new HttpHeaders({ 'X-Store-Id': String(TIENDA_PROVISIONAL_ID) });

    listar(filtro: FiltroPedidos = {}): Observable<PaginaPedidos<PedidoResumen>> {
        let params = new HttpParams().set('page', filtro.pagina ?? 0).set('size', filtro.tamano ?? 20);
        for (const estado of filtro.estados ?? []) {
            params = params.append('status', estado);
        }
        if (filtro.pedidoId) {
            params = params.set('orderId', filtro.pedidoId);
        }
        return this.http.get<PaginaPedidos<PedidoResumen>>(this.url, { params, headers: this.headers });
    }

    detalle(id: number): Observable<PedidoDetalle> {
        return this.http.get<PedidoDetalle>(`${this.url}/${id}`, { headers: this.headers });
    }

    iniciarPreparacion(id: number): Observable<ResultadoCambioEstado> {
        return this.http.post<ResultadoCambioEstado>(`${this.url}/${id}/start-preparation`, {}, { headers: this.headers });
    }

    registrarNovedad(id: number, tipo: TipoNovedad, descripcion: string): Observable<NovedadPedido> {
        return this.http.post<NovedadPedido>(`${this.url}/${id}/issues`,
            { type: tipo, description: descripcion }, { headers: this.headers });
    }

    resolverNovedad(id: number, novedadId: number): Observable<NovedadPedido> {
        return this.http.post<NovedadPedido>(`${this.url}/${id}/issues/${novedadId}/resolve`, {}, { headers: this.headers });
    }

    listoParaDespacho(id: number): Observable<ResultadoListoParaDespacho> {
        return this.http.post<ResultadoListoParaDespacho>(`${this.url}/${id}/ready-for-dispatch`, {}, { headers: this.headers });
    }

    /** Reintento manual del envío: es idempotente, repetirlo no crea otro envío. */
    reintentarEnvio(id: number): Observable<ResultadoEnvio> {
        return this.http.post<ResultadoEnvio>(`${this.url}/${id}/shipment`, {}, { headers: this.headers });
    }

    cancelar(id: number, motivo: MotivoCancelacion, detalle: string): Observable<ResultadoCancelacion> {
        return this.http.post<ResultadoCancelacion>(`${this.url}/${id}/cancel`,
            { reasonCode: motivo, details: detalle.trim() || null }, { headers: this.headers });
    }
}
