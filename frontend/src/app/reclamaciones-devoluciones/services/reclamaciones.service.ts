import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { Reclamacion } from '../models/reclamacion.model';
/** Aísla las futuras operaciones de posventa y sus estados. */
@Injectable({ providedIn: 'root' })
export class ReclamacionesService { /** Devuelve los casos activos para la sección de ayuda. */ obtenerActivas(): Observable<Reclamacion[]> { return of([]); } }
