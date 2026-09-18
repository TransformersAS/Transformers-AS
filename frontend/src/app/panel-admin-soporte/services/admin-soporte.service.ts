import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { CasoSoporte } from '../models/caso-soporte.model';
/** Puerta de enlace para colas de moderación y soporte. */
@Injectable({ providedIn: 'root' })
export class AdminSoporteService { /** Entrega casos pendientes para el rol autorizado. */ obtenerCola(): Observable<CasoSoporte[]> { return of([]); } }
