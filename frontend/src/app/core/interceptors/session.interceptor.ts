import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { API_BASE } from '../config/api.config';

const METODOS_SEGUROS = ['GET', 'HEAD', 'OPTIONS'];
const ENDPOINTS_SIN_LIMPIEZA_POR_401 = new Set([
  `${API_BASE}/auth/csrf`,
  `${API_BASE}/auth/login`,
  `${API_BASE}/auth/logout`,
  `${API_BASE}/auth/password-recovery/request`,
  `${API_BASE}/auth/password-recovery/confirm`
]);

/** El backend responde 401 con este código a una sesión válida cuya cuenta aún no tiene tienda (CU-18). */
const CODIGO_SESION_VALIDA_SIN_TIENDA = 'STORE_IDENTITY_MISSING';

function esSesionValidaSinTienda(error: HttpErrorResponse): boolean {
  return (error.error as { code?: string } | null)?.code === CODIGO_SESION_VALIDA_SIN_TIENDA;
}

/**
 * Llamadas a la API con la cookie de sesión y, en las que modifican datos, el token CSRF. Solo
 * actúa sobre rutas relativas de la API propia. Un 401 protegido limpia el estado local, salvo el de una sesión
 * válida sin tienda asociada, sin iniciar peticiones adicionales ni reintentar login, logout o recuperación.
 */
export const sessionInterceptor: HttpInterceptorFn = (req, next) => {
  const ruta = req.url.split(/[?#]/, 1)[0];
  if (ruta !== API_BASE && !ruta.startsWith(`${API_BASE}/`)) {
    return next(req);
  }
  const auth = inject(AuthService);
  const conCookie = req.clone({ withCredentials: true });
  const modifica = !METODOS_SEGUROS.includes(req.method);

  const enviar = modifica
    ? auth.asegurarCsrf().pipe(
        switchMap(csrf => next(conCookie.clone({ setHeaders: { [csrf.header]: csrf.token } }))))
    : next(conCookie);

  return enviar.pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401 && !ENDPOINTS_SIN_LIMPIEZA_POR_401.has(ruta) && !esSesionValidaSinTienda(error)) {
        auth.sesionExpirada();
      } else if (error.status === 403 && modifica) {
        // Un 403 al modificar suele ser un token CSRF vencido: la próxima vez se pide uno nuevo.
        auth.invalidarCsrf();
      }
      return throwError(() => error);
    })
  );
};
