import { Injectable } from '@angular/core';

/** Punto único para el usuario activo; después alojará tokens y llamadas de autenticación. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  /** El mock evita una pantalla impersonal antes de implementar login. */
  obtenerNombreVisible(): string { return 'Fran'; }
}
