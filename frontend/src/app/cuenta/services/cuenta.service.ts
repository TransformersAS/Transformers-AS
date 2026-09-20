import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { Usuario } from '../models/usuario.model';

/** Fachada de compatibilidad: devuelve la cuenta real de /me, no un perfil ampliado. */
@Injectable({ providedIn: 'root' })
export class CuentaService {
  private readonly auth = inject(AuthService);

  obtenerPerfil(): Observable<Usuario | null> {
    return this.auth.obtenerCuentaActual();
  }
}
