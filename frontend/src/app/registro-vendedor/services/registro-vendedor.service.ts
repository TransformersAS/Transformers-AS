import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE } from '../../core/config/api.config';
import { CondicionesVendedor, ResultadoRegistro } from '../models/registro-vendedor.model';

/**
 * Registro de vendedores (CU-12). Las condiciones, el registro de una cuenta nueva y la confirmación no exigen
 * sesión; habilitar el rol con una cuenta existente sí. La sesión y el token CSRF los aporta sessionInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class RegistroVendedorService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/sellers`;

  obtenerCondiciones(): Observable<CondicionesVendedor> {
    return this.http.get<CondicionesVendedor>(`${this.url}/terms`);
  }

  /** Sin cuenta: crea la cuenta y la tienda. */
  registrar(email: string, password: string, storeName: string, acceptTerms: boolean): Observable<ResultadoRegistro> {
    return this.http.post<ResultadoRegistro>(`${this.url}/register`, { email, password, storeName, acceptTerms });
  }

  /** Con cuenta: habilita el rol de vendedor con la misma cuenta y credenciales. */
  habilitar(storeName: string, acceptTerms: boolean): Observable<ResultadoRegistro> {
    return this.http.post<ResultadoRegistro>(`${this.url}/enable`, { storeName, acceptTerms });
  }

  /** Confirma el registro: el correo de la cuenta y el nombre de la tienda que se registró. */
  confirmarRegistro(email: string, storeName: string): Observable<void> {
    return this.http.post<void>(`${this.url}/verify-email`, { email, storeName });
  }
}
