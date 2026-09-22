import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, catchError, map, of, switchMap, tap, throwError } from 'rxjs';

import { API_BASE } from '../config/api.config';

import {
  CambioContrasena, CambioRol, ConfirmacionRecuperacion, CredencialesLogin,
  CuentaSesion, RespuestaCsrf, RespuestaRecuperacion, Rol, SesionActiva,
  SolicitudRecuperacion, TokenCsrf
} from '../models/auth.model';

// Compatibilidad con los consumidores existentes; la definición es única.
export type { CuentaSesion, Rol, TokenCsrf } from '../models/auth.model';

/**
 * Sesión del usuario. El servidor mantiene la sesión en una cookie HttpOnly (el frontend nunca ve la
 * contraseña ni el identificador de sesión); aquí solo se guarda quién es y el token CSRF que exigen
 * las peticiones que modifican datos. Los permisos los decide siempre el backend.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private csrf: TokenCsrf | null = null;

  private readonly _cuenta = signal<CuentaSesion | null>(null);
  readonly cuenta = this._cuenta.asReadonly();
  readonly autenticada = computed(() => this._cuenta() !== null);

  /** Nombre para saludar: la parte local del correo, o un trato neutro sin sesión. */
  obtenerNombreVisible(): string {
    const correo = this._cuenta()?.email;
    return correo ? correo.split('@')[0] : 'visitante';
  }

  /** Recupera la sesión si el navegador todavía tiene la cookie (por ejemplo tras recargar la página). */
  restaurar(): void {
    this.obtenerCuentaActual().subscribe({ error: () => this.olvidar() });
  }

  iniciarSesion(email: string, password: string): Observable<CuentaSesion> {
    const credenciales: CredencialesLogin = { email, password };
    const cuerpo = new HttpParams().set('email', credenciales.email).set('password', credenciales.password).toString();
    return this.refrescarCsrf().pipe(
      switchMap(() => this.http.post(`${API_BASE}/auth/login`, cuerpo,
        { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })),
      // El token CSRF cambia al autenticarse: hay que pedir el nuevo.
      switchMap(() => this.refrescarCsrf()),
      switchMap(() => this.http.get<CuentaSesion>(`${API_BASE}/auth/me`)),
      tap(cuenta => this._cuenta.set(cuenta))
    );
  }

  reenviarVerificacion(email: string, password: string): Observable<void> {
    return this.http.post<void>(`${API_BASE}/auth/email-verification/resend`, { email, password });
  }

  confirmarCorreo(token: string): Observable<void> {
    return this.http.post<void>(`${API_BASE}/auth/email-verification/confirm`, { token });
  }

  cambiarRol(rol: Rol): Observable<CuentaSesion> {
    const cambio: CambioRol = { role: rol };
    return this.http.put<CuentaSesion>(`${API_BASE}/auth/active-role`, cambio).pipe(
      tap(cuenta => this._cuenta.set(cuenta))
    );
  }

  cerrarSesion(): Observable<void> {
    return this.http.post(`${API_BASE}/auth/logout`, {}, { responseType: 'text' }).pipe(
      catchError(() => of(null)),
      tap(() => this.olvidar()),
      map(() => undefined)
    );
  }

  /** /me no proporciona nombre ni perfil: solo identidad y roles de la sesión. */
  obtenerCuentaActual(): Observable<CuentaSesion | null> {
    return this.http.get<CuentaSesion>(`${API_BASE}/auth/me`).pipe(
      tap(cuenta => this._cuenta.set(cuenta)),
      catchError((error: HttpErrorResponse) => {
        if (error.status === 401) {
          this.olvidar();
          return of(null);
        }
        return throwError(() => error);
      })
    );
  }

  listarSesiones(): Observable<SesionActiva[]> {
    return this.http.get<SesionActiva[]>(`${API_BASE}/auth/sessions`);
  }

  /** Recibe una sesión de listarSesiones(); solo limpia la cuenta si se revoca la actual. */
  revocarSesion(sesion: SesionActiva): Observable<void> {
    return this.http.delete<void>(`${API_BASE}/auth/sessions/${encodeURIComponent(sesion.id)}`).pipe(
      tap(() => {
        if (sesion.current) this.olvidar();
      })
    );
  }

  /** El backend conserva esta sesión y revoca las demás de la cuenta. */
  cambiarContrasena(cambio: CambioContrasena): Observable<void> {
    return this.http.put<void>(`${API_BASE}/auth/password`, cambio);
  }

  solicitarRecuperacion(solicitud: SolicitudRecuperacion): Observable<RespuestaRecuperacion> {
    return this.http.post<RespuestaRecuperacion>(`${API_BASE}/auth/password-recovery/request`, solicitud);
  }

  confirmarRecuperacion(confirmacion: ConfirmacionRecuperacion): Observable<void> {
    return this.http.post<void>(`${API_BASE}/auth/password-recovery/confirm`, confirmacion).pipe(
      // El reset revoca las sesiones de la cuenta recuperada. /me permite conservar
      // una sesión que pertenezca a otra cuenta, sin deducir identidades del token.
      tap(() => this.invalidarCsrf()),
      switchMap(() => this.obtenerCuentaActual().pipe(
        // El reset ya tuvo éxito; un fallo de sincronización no permite repetir el token.
        // obtenerCuentaActual conserva la limpieza de sesión ante un 401.
        catchError(() => of(null))
      )),
      map(() => undefined)
    );
  }

  /** El servidor rechazó la sesión (expiró o se cerró en otro lugar): se olvida sin llamar a la API. */
  sesionExpirada(): void {
    this.olvidar();
  }

  csrfActual(): TokenCsrf | null {
    return this.csrf;
  }

  /** Devuelve el token CSRF en caché o lo pide al servidor. */
  asegurarCsrf(): Observable<TokenCsrf> {
    return this.csrf ? of(this.csrf) : this.refrescarCsrf();
  }

  invalidarCsrf(): void {
    this.csrf = null;
  }

  private refrescarCsrf(): Observable<TokenCsrf> {
    return this.http.get<RespuestaCsrf>(`${API_BASE}/auth/csrf`).pipe(
      map(respuesta => ({ header: respuesta.headerName, token: respuesta.token })),
      tap(csrf => (this.csrf = csrf))
    );
  }

  private olvidar(): void {
    this._cuenta.set(null);
    this.csrf = null;
  }
}
