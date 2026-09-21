import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import { ItemInventario, MovimientoInventario, ResultadoCarga, TipoCarga } from '../models/inventario-vendedor.model';
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

  // ---------- Cargas masivas con Excel ----------

  /** Descarga la plantilla vacía de productos o la de inventario (que ya trae los productos de la tienda). */
  descargarPlantilla(tipo: TipoCarga): Observable<Blob> {
    return this.http.get(`${this.url}/templates/${tipo}`, { headers: this.headers, responseType: 'blob' });
  }

  /** Sube la plantilla ya llena. Es todo o nada: si alguna fila falla el backend no guarda nada. */
  cargar(tipo: TipoCarga, archivo: File): Observable<ResultadoCarga> {
    const datos = new FormData();
    datos.append('file', archivo);
    return this.http.post<ResultadoCarga>(`${this.url}/imports/${tipo}`, datos, { headers: this.headers });
  }
}
