import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, catchError, map, of, switchMap, tap } from 'rxjs';

export const API_BASE = 'http://localhost:8080/api';

export type Rol = 'COMPRADOR' | 'VENDEDOR' | 'ADMIN' | 'SOPORTE';

/** Cuenta de la sesión activa, tal como la entrega GET /api/auth/me. */
export interface CuentaSesion {
  accountId: number;
  email: string;
  roles: Rol[];
  /** Es null cuando la cuenta tiene varios roles y aún no eligió uno. */
  activeRole: Rol | null;
}

export interface TokenCsrf {
  header: string;
  token: string;
}

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
    this.http.get<CuentaSesion>(`${API_BASE}/auth/me`).pipe(catchError(() => of(null)))
      .subscribe(cuenta => this._cuenta.set(cuenta));
  }

  iniciarSesion(email: string, password: string): Observable<CuentaSesion | null> {
    const cuerpo = new HttpParams().set('email', email).set('password', password).toString();
    return this.refrescarCsrf().pipe(
      switchMap(() => this.http.post(`${API_BASE}/auth/login`, cuerpo,
        { headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, responseType: 'text' })),
      // El token CSRF cambia al autenticarse: hay que pedir el nuevo.
      switchMap(() => this.refrescarCsrf()),
      switchMap(() => this.http.get<CuentaSesion>(`${API_BASE}/auth/me`)),
      tap(cuenta => this._cuenta.set(cuenta))
    );
  }

  cambiarRol(rol: Rol): Observable<CuentaSesion> {
    return this.http.put<CuentaSesion>(`${API_BASE}/auth/active-role`, { role: rol }).pipe(
      tap(cuenta => this._cuenta.set(cuenta))
    );
  }

  cerrarSesion(): Observable<unknown> {
    return this.http.post(`${API_BASE}/auth/logout`, {}, { responseType: 'text' }).pipe(
      catchError(() => of(null)),
      tap(() => this.olvidar())
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
    return this.http.get<{ headerName: string; token: string }>(`${API_BASE}/auth/csrf`).pipe(
      map(respuesta => ({ header: respuesta.headerName, token: respuesta.token })),
      tap(csrf => (this.csrf = csrf))
    );
  }

  private olvidar(): void {
    this._cuenta.set(null);
    this.csrf = null;
  }
}
