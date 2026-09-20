import { API_BASE } from '../../core/config/api.config';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CheckoutPreviewRequest,
  CheckoutPreviewResponse
} from '../models/checkout.model';

@Injectable({
  providedIn: 'root'
})
export class CheckoutService {

  private readonly http = inject(HttpClient);

  private readonly apiUrl =
    `${API_BASE}/checkout`;

  preview(
    request: CheckoutPreviewRequest
  ): Observable<CheckoutPreviewResponse> {

    return this.http.post<CheckoutPreviewResponse>(
      `${this.apiUrl}/preview`,
      request
    );
  }
}