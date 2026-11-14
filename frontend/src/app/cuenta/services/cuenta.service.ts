import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { API_BASE } from '../../core/config/api.config';
import { PerfilPersonal, CambioPerfil, PerfilGuardado, ElegibilidadCierre } from '../models/perfil-personal.model';
import { AuthService } from '../../core/services/auth.service';
import { Usuario } from '../models/usuario.model';

/** Conserva la fachada de sesión existente y añade las operaciones personales de CU09. */
@Injectable({ providedIn: 'root' })
export class CuentaService {
  private readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/account/profile`;

  consultarPerfilPersonal() { return this.http.get<PerfilPersonal>(this.url); }
  guardarPerfil(cambio:CambioPerfil) { return this.http.put<PerfilGuardado>(this.url,cambio); }
  reenviarCambioCorreo(currentPassword:string) { return this.http.post<PerfilGuardado>(`${this.url}/email/resend`,{currentPassword}); }
  confirmarCambioCorreo(token:string) {
    return this.http.post<void>(`${this.url}/email/confirm`,{token}).pipe(tap(()=>this.auth.finalizarOperacionDeCuenta()));
  }
  consultarCierre() { return this.http.get<ElegibilidadCierre>(`${this.url}/closure`); }
  cerrarCuenta(currentPassword:string,version:number,confirmClosure:boolean) {
    return this.http.post<void>(`${this.url}/closure`,{currentPassword,version,confirmClosure}).pipe(tap(()=>this.auth.finalizarOperacionDeCuenta()));
  }

  obtenerPerfil(): Observable<Usuario | null> {
    return this.auth.obtenerCuentaActual();
  }
}
