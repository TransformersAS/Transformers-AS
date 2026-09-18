import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { Tienda } from '../models/tienda.model';
/** Entrada única para inventario, promociones y métricas de la tienda autenticada. */
@Injectable({ providedIn: 'root' })
export class VendedorService { /** Luego mapeará GET /vendedor/tienda a este modelo de pantalla. */ obtenerTienda(): Observable<Tienda | null> { return of(null); } }
