import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/config/api.config';
import { CancelacionPedido, DetallePedido, Pedido, ResultadoCancelacion } from '../models/pedido.model';

@Injectable({ providedIn: 'root' })
export class PedidosService {
  private readonly http = inject(HttpClient);

  listarMisPedidos(): Observable<Pedido[]> {
    return this.http.get<Pedido[]>(`${API_BASE}/orders`);
  }

  obtenerPedido(id: number): Observable<DetallePedido> {
    return this.http.get<DetallePedido>(`${API_BASE}/orders/${id}`);
  }

  cancelarPedido(id: number, motivo: CancelacionPedido): Observable<ResultadoCancelacion> {
    return this.http.post<ResultadoCancelacion>(`${API_BASE}/orders/${id}/cancellation`, motivo);
  }
}
