import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { Pedido } from '../models/pedido.model';
/** Fachada de pedidos: conserva el contrato que consumirá la pantalla de seguimiento. */
@Injectable({ providedIn: 'root' })
export class PedidosService { /** Sustituir este mock por GET /pedidos no cambia a sus consumidores. */ obtenerMisPedidos(): Observable<Pedido[]> { return of([]); } }
