import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import { ConsultaAnalitica, InformeVentas, OpcionesAnalitica } from '../models/analitica.model';

/**
 * Analítica de ventas de la tienda del vendedor (CU-26). No envía X-Store-Id: el backend usa la tienda de la cuenta,
 * así cada vendedor solo ve la suya. La sesión y el CSRF los aporta sessionInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class AnaliticaService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/seller/analytics`;

  analizar(consulta: ConsultaAnalitica): Observable<InformeVentas> {
    return this.http.get<InformeVentas>(this.url, { params: this.parametros(consulta) });
  }

  opciones(): Observable<OpcionesAnalitica> {
    return this.http.get<OpcionesAnalitica>(`${this.url}/options`);
  }

  /** El mismo informe en Excel, con los periodos y filtros de la consulta. */
  exportar(consulta: ConsultaAnalitica): Observable<Blob> {
    return this.http.get(`${this.url}/export`, { params: this.parametros(consulta), responseType: 'blob' });
  }

  private parametros(consulta: ConsultaAnalitica): HttpParams {
    let params = new HttpParams().set('from', consulta.from).set('to', consulta.to);
    if (consulta.compareFrom && consulta.compareTo) {
      params = params.set('compareFrom', consulta.compareFrom).set('compareTo', consulta.compareTo);
    }
    if (consulta.productId !== null) {
      params = params.set('productId', String(consulta.productId));
    }
    if (consulta.category) {
      params = params.set('category', consulta.category);
    }
    return params;
  }
}
