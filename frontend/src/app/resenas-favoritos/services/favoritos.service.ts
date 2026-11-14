import { Injectable, inject, effect, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../core/services/auth.service';
import { API_BASE } from '../../core/config/api.config';
export interface Favorito { id: number; name: string; price: number; stock: number; active: boolean; category: string; images: string[]; variants: {name: string; price: number; stock: number}[]; }
export interface Alerta { id: string; productId: number; productName: string; kind: string; variantName: string | null; targetPrice: number | null; status: string; revision: number; }
export interface Condicion { productId: number; kind: string; variantName: string | null; targetPrice: number | null; }
@Injectable({ providedIn: 'root' })
export class FavoritosService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly url = `${API_BASE}/favorites`;
  readonly ids = signal<number[]>([]);
  readonly error = signal('');
  readonly ocupados = signal<number[]>([]);
  constructor() {
    effect(onCleanup => {
      const cuenta = this.auth.cuenta();
      this.ids.set([]); this.error.set(''); this.ocupados.set([]);
      if (cuenta?.activeRole !== 'COMPRADOR') return;
      const request = this.http.get<number[]>(`${this.url}/ids`).subscribe({next: ids => this.ids.set(Array.isArray(ids) ? ids : []), error: () => this.error.set('No se pudieron cargar tus favoritos.')});
      onCleanup(() => request.unsubscribe());
    }, { allowSignalWrites: true });
  }
  toggle(id: number) {
    if (this.ocupados().includes(id)) return;
    const cuenta = this.auth.cuenta();
    if (cuenta?.activeRole !== 'COMPRADOR') return;
    const quitar = this.ids().includes(id);
    this.ocupados.update(ids => [...ids, id]); this.error.set('');
    (quitar ? this.http.delete<void>(`${this.url}/${id}`) : this.http.put<void>(`${this.url}/${id}`, {})).subscribe({
      next: () => { if (this.auth.cuenta() !== cuenta) return;
        this.ids.update(ids => quitar ? ids.filter(value => value !== id) : [...new Set([...ids, id])]);
        this.ocupados.update(ids => ids.filter(value => value !== id)); },
      error: () => { if (this.auth.cuenta() !== cuenta) return; this.ocupados.update(ids => ids.filter(value => value !== id)); this.error.set('No se pudo actualizar el favorito. Intenta nuevamente.'); }
    });
  }
  listar(page: number) { return this.http.get<Favorito[]>(this.url, {params: {page}}); }
  obtenerIds() { return this.http.get<number[]>(`${this.url}/ids`); }
  producto(id: number) { return this.http.get<{variants: Favorito['variants']}>(`${API_BASE}/products/${id}`); }
  alertas() { return this.http.get<Alerta[]>(`${this.url}/alerts`); }
  crear(input: Condicion) { return this.http.post<{alreadySatisfied: boolean}>(`${this.url}/alerts`,input); }
  editar(id: string,input: Condicion) { return this.http.put<{alreadySatisfied: boolean}>(`${this.url}/alerts/${id}`,input); }
  estado(id: string,status: string) { return this.http.patch<void>(`${this.url}/alerts/${id}`,{status}); }
  eliminar(id: string) { return this.http.delete<void>(`${this.url}/alerts/${id}`); }
}
