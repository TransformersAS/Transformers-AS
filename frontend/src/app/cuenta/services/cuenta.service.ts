import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { Usuario } from '../models/usuario.model';
/** Centraliza la obtención de perfil para no dispersar detalles de API en las vistas. */
@Injectable({ providedIn: 'root' })
export class CuentaService { /** El valor nulo expresa una sesión aún no autenticada. */ obtenerPerfil(): Observable<Usuario | null> { return of(null); } }
