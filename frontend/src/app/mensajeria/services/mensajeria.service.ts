import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { API_BASE } from '../../core/config/api.config';
import { Conversacion, Mensaje } from '../models/conversacion.model';
@Injectable({ providedIn: 'root' })
export class MensajeriaService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_BASE}/conversations`;
  readonly abierta = signal(false);
  readonly seleccionada = signal<number | null>(null);
  abrir(id: number | null = null) { this.seleccionada.set(id); this.abierta.set(true); }
  cerrar() { this.abierta.set(false); this.seleccionada.set(null); }
  iniciar(productId: number, orderId: number | null = null) { return this.http.post<Conversacion>(this.url, { productId, orderId }); }
  obtenerConversaciones(archived = false, page = 0) { return this.http.get<Conversacion[]>(this.url, { params: { archived, page } }); }
  detalle(id: number) { return this.http.get<Conversacion>(`${this.url}/${id}`); }
  historial(id: number, after = 0, before = Number.MAX_SAFE_INTEGER) { return this.http.get<Mensaje[]>(`${this.url}/${id}/messages`, { params: { after, before, size: 50 } }); }
  enviar(id: number, text: string, clientKey: string, image: File | null) {
    if (!image) return this.http.post<Mensaje>(`${this.url}/${id}/messages`, { text, clientKey });
    const form = new FormData(); form.append('text', text); form.append('clientKey', clientKey); form.append('image', image);
    return this.http.post<Mensaje>(`${this.url}/${id}/messages`, form);
  }
  leer(id: number, through: number) { return this.http.post<void>(`${this.url}/${id}/read`, { through }); }
  estado(id: number, action: 'archive' | 'closed', value: boolean) { return this.http.post<void>(`${this.url}/${id}/${action}`, { value }); }
  imagen(id: number) { return `${this.url}/images/${id}`; }
}
