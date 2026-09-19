import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { API_BASE, AuthService } from '../services/auth.service';

const METODOS_SEGUROS = ['GET', 'HEAD', 'OPTIONS'];

/**
 * Llamadas a la API con la cookie de sesión y, en las que modifican datos, el token CSRF. Solo
 * actúa sobre la API propia, nunca sobre terceros. Si el servidor responde 401 fuera del inicio de
 * sesión, la sesión local se olvida.
 */
export const sessionInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(API_BASE)) {
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
      if (error.status === 401 && !req.url.startsWith(`${API_BASE}/auth/`)) {
        auth.sesionExpirada();
      } else if (error.status === 403 && modifica) {
        // Un 403 al modificar suele ser un token CSRF vencido: la próxima vez se pide uno nuevo.
        auth.invalidarCsrf();
      }
      return throwError(() => error);
    })
  );
};
