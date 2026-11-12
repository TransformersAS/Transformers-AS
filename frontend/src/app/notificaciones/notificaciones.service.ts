import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { API_BASE } from '../core/config/api.config';
export interface Aviso { id: number; title: string; message: string; createdAt: string; orderId: number | null; productId?: number | null; conversationId?: number | null; }
export interface PaginaAvisos { items: Aviso[]; hasMore: boolean; }
@Injectable({ providedIn: 'root' })
export class NotificacionesService {
  private readonly http = inject(HttpClient);
  listar(vendedor: boolean, page: number) {
    return this.http.get<PaginaAvisos>(`${API_BASE}/${vendedor ? 'seller/' : ''}notifications`, { params: { page, size: 20 } });
  }
}
