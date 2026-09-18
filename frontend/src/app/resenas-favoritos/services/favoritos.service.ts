import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
/** Mantiene favoritos locales mientras se incorpora la persistencia por usuario. */
@Injectable({ providedIn: 'root' })
export class FavoritosService { private readonly ids = new BehaviorSubject<readonly string[]>([]); /** Expone una colección inmutable para las vistas. */ readonly ids$ = this.ids.asObservable(); }
