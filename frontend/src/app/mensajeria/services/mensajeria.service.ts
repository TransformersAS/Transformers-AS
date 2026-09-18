import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { Conversacion } from '../models/conversacion.model';
/** Contrato de chat desacoplado del mecanismo final, REST o WebSocket. */
@Injectable({ providedIn: 'root' })
export class MensajeriaService { /** Lista conversaciones de la sesión actual. */ obtenerConversaciones(): Observable<Conversacion[]> { return of([]); } }
