import { API_BASE } from '../../core/config/api.config';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  PaymentRequest,
  PaymentResponse, HostedCheckout
} from '../models/payment.model';

@Injectable({
  providedIn: 'root'
})
export class PaymentService {

  startCheckout(request: { addressId: number; shippingMethod: string; couponCode: string }): Observable<HostedCheckout> {
    return this.http.post<HostedCheckout>(`${this.apiUrl}/mercadopago/checkouts`, request);
  }

  checkoutStatus(id: string): Observable<HostedCheckout> {
    return this.http.get<HostedCheckout>(`${this.apiUrl}/mercadopago/checkouts/${encodeURIComponent(id)}`);
  }

  refreshCheckout(id: string, paymentId: string): Observable<HostedCheckout> {
    return this.http.post<HostedCheckout>(`${this.apiUrl}/mercadopago/checkouts/${encodeURIComponent(id)}/refresh`, { paymentId });
  }

  private readonly http = inject(HttpClient);

  private readonly apiUrl =
    `${API_BASE}/payments`;

  process(
    request: PaymentRequest
  ): Observable<PaymentResponse> {

    return this.http.post<PaymentResponse>(
      `${this.apiUrl}/process`,
      request
    );
  }
}
