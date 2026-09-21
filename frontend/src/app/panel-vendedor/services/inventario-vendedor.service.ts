import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import { ItemInventario, MovimientoInventario } from '../models/inventario-vendedor.model';
import { TIENDA_PROVISIONAL_ID } from './pedidos-vendedor.service';

/**
 * Inventario de la tienda del vendedor (CU-15). Usa la misma tienda provisional (X-Store-Id) que los pedidos
 * recibidos y los productos; la sesión y el CSRF los aporta sessionInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class InventarioVendedorService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/seller/inventory`;
  private readonly headers = new HttpHeaders({ 'X-Store-Id': String(TIENDA_PROVISIONAL_ID) });

  listar(texto: string, soloBajos: boolean): Observable<ItemInventario[]> {
    let params = new HttpParams();
    if (texto.trim()) {
      params = params.set('q', texto.trim());
    }
    if (soloBajos) {
      params = params.set('onlyLow', 'true');
    }
    return this.http.get<ItemInventario[]>(this.url, { params, headers: this.headers });
  }

  /** Llegó mercancía: suma unidades. El motivo es opcional. */
  registrarEntrada(productoId: number, quantity: number, reason: string): Observable<ItemInventario> {
    return this.http.post<ItemInventario>(`${this.url}/${productoId}/entries`, { quantity, reason },
      { headers: this.headers });
  }

  /** Conteo real: fija el stock. El motivo es obligatorio. */
  registrarAjuste(productoId: number, newStock: number, reason: string): Observable<ItemInventario> {
    return this.http.post<ItemInventario>(`${this.url}/${productoId}/adjustments`, { newStock, reason },
      { headers: this.headers });
  }

  configurarMinimo(productoId: number, minStock: number): Observable<ItemInventario> {
    return this.http.put<ItemInventario>(`${this.url}/${productoId}/minimum`, { minStock }, { headers: this.headers });
  }

  historial(productoId: number): Observable<MovimientoInventario[]> {
    return this.http.get<MovimientoInventario[]>(`${this.url}/${productoId}/movements`, { headers: this.headers });
  }
}
