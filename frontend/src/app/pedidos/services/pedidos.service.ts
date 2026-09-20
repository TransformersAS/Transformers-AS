import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/config/api.config';
import { DetallePedido, Pedido } from '../models/pedido.model';

@Injectable({ providedIn: 'root' })
export class PedidosService {
  private readonly http = inject(HttpClient);

  listarMisPedidos(): Observable<Pedido[]> {
    return this.http.get<Pedido[]>(`${API_BASE}/orders`);
  }

  obtenerPedido(id: number): Observable<DetallePedido> {
    return this.http.get<DetallePedido>(`${API_BASE}/orders/${id}`);
  }

  solicitarCancelacion(id: number): Observable<void> {
    return this.http.post<void>(`${API_BASE}/orders/${id}/cancellation`, {});
  }
}
