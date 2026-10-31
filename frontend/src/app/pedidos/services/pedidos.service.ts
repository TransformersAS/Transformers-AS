import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/config/api.config';
import { CancelacionPedido, DetallePedido, FiltrosPedidos, Pedido, ResultadoCancelacion } from '../models/pedido.model';

@Injectable({ providedIn: 'root' })
export class PedidosService {
  private readonly http = inject(HttpClient);

  listarMisPedidos(filtros: FiltrosPedidos = {}): Observable<Pedido[]> {
    const params: Record<string, string> = {};
    if (filtros.from) params['from'] = filtros.from;
    if (filtros.to) params['to'] = filtros.to;
    if (filtros.status) params['status'] = filtros.status;
    return this.http.get<Pedido[]>(`${API_BASE}/orders`, { params });
  }

  obtenerPedido(id: number): Observable<DetallePedido> {
    return this.http.get<DetallePedido>(`${API_BASE}/orders/${id}`);
  }

  obtenerComprobante(id: number, download = false): Observable<Blob> {
    return this.http.get(`${API_BASE}/orders/${id}/receipt`, {
      params: { download: String(download) }, responseType: 'blob'
    });
  }

  cancelarPedido(id: number, motivo: CancelacionPedido): Observable<ResultadoCancelacion> {
    return this.http.post<ResultadoCancelacion>(`${API_BASE}/orders/${id}/cancellation`, motivo);
  }
}
